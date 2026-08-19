package com.example.personalmemoryai.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.database.PersonEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Multi-signal face retrieval. Learned face identity embeddings remain the
 * dominant signal; facial geometry, pose and image quality provide independent
 * corroboration. Results are visual similarity evidence, not proof of a
 * real-world identity.
 */
class FaceSearchService(private val context: Context) : AutoCloseable {
    private val database = AppDatabase.getInstance(context.applicationContext)
    private val diagnostics = DiagnosticsManager.get(context)
    private val analyzer by lazy { MediaPipeFaceAnalyzer(context.applicationContext) }
    private val embeddingModel by lazy { TFLiteFaceEmbeddingModel(context.applicationContext, FACE_MODEL_FILE) }

    data class FaceMatch(
        val image: ImageEntity,
        val face: FaceEntity,
        val person: PersonEntity?,
        val identitySimilarity: Float,
        val shapeSimilarity: Float,
        val poseSimilarity: Float,
        val quality: Float,
        val compositeScore: Float,
        val confidenceBand: ConfidenceBand
    )

    enum class ConfidenceBand { VERY_HIGH, HIGH, MEDIUM, LOW }

    suspend fun search(queryUri: Uri, limit: Int = 30): List<FaceMatch> = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("FACE_SEARCH", mapOf("limit" to limit.toString(), "model" to ACTIVE_MODEL))
        try {
            val bitmap = context.contentResolver.openInputStream(queryUri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("تعذر قراءة صورة البحث عن الوجه")
            try {
                run.stage("DETECT_QUERY", "Detecting query faces, landmarks, pose and identity embeddings")
                val queries = FaceAnalysisService(analyzer, embeddingModel).analyze(bitmap)
                val usableQueries = queries.filter { it.embedding != null && it.landmarkShape != null && it.pose != null && it.usableForMatching }
                if (usableQueries.isEmpty()) error("لم يتم العثور على وجه قابل للمطابقة في صورة البحث")

                val storedFaces = database.faceDao().getMatchableFaces()
                val identityEmbeddings = database.embeddingDao().getAllForFaceSearch()
                    .filter { it.modelName.equals(ACTIVE_MODEL, true) }
                    .groupBy { it.ownerId }
                    .mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }
                val shapeEmbeddings = loadShapeEmbeddings()
                val imageIds = storedFaces.map { it.imageId }.distinct()
                val images = database.imageDao().getByIds(imageIds).associateBy { it.id }
                val persons = loadPersons(storedFaces)

                run.stage(
                    "LOAD_INDEX",
                    "Loaded active multi-signal face index",
                    mapOf(
                        "faces" to storedFaces.size.toString(),
                        "identityEmbeddings" to identityEmbeddings.size.toString(),
                        "shapeEmbeddings" to shapeEmbeddings.size.toString(),
                        "model" to ACTIVE_MODEL
                    )
                )

                val results = mutableListOf<FaceMatch>()
                for (stored in storedFaces) {
                    val identity = identityEmbeddings[stored.id] ?: continue
                    val shape = shapeEmbeddings[stored.id]
                    val image = images[stored.imageId] ?: continue
                    val person = persons[stored.personId]
                    val storedPose = FacePoseEstimator.Pose(
                        stored.rotationX ?: 0f,
                        stored.rotationY ?: 0f,
                        stored.rotationZ ?: 0f
                    )

                    for (query in usableQueries) {
                        val queryIdentity = query.embedding ?: continue
                        if (identity.vector.size != queryIdentity.size) continue

                        val identityCosine = FaceSimilarity.cosineSimilarity(queryIdentity, identity.vector)
                        val identity01 = identityCosine.coerceIn(0f, 1f)
                        val shapeSimilarity = if (shape != null && query.landmarkShape != null) {
                            FaceShapeEncoder.similarity(query.landmarkShape, shape.vector)
                        } else 0f
                        val poseSimilarity = FacePoseEstimator.similarity(query.pose, storedPose)
                        val storedQuality = stored.qualityScore.coerceIn(0f, 1f)
                        val queryQuality = query.quality.score.coerceIn(0f, 1f)
                        val jointQuality = sqrt((queryQuality * storedQuality).coerceIn(0f, 1f))

                        // Independent signals. Identity is dominant; shape and pose only corroborate.
                        val signalScore = (
                            0.74f * identity01 +
                            0.18f * shapeSimilarity +
                            0.08f * poseSimilarity
                        ).coerceIn(0f, 1f)
                        val qualityFactor = 0.68f + 0.32f * jointQuality
                        val composite = (signalScore * qualityFactor).coerceIn(0f, 1f)

                        results += FaceMatch(
                            image = image,
                            face = stored,
                            person = person,
                            identitySimilarity = identity01,
                            shapeSimilarity = shapeSimilarity,
                            poseSimilarity = poseSimilarity,
                            quality = jointQuality,
                            compositeScore = composite,
                            confidenceBand = band(composite, identity01, jointQuality)
                        )
                    }
                }

                val ranked = results
                    .groupBy { it.face.id }
                    .values
                    .mapNotNull { candidates -> candidates.maxByOrNull { it.compositeScore } }
                    .sortedWith(compareByDescending<FaceMatch> { it.compositeScore }.thenByDescending { it.identitySimilarity })
                    .take(limit)

                run.success(
                    "Face search completed",
                    mapOf(
                        "queryFaces" to usableQueries.size.toString(),
                        "candidates" to results.size.toString(),
                        "results" to ranked.size.toString(),
                        "model" to ACTIVE_MODEL
                    )
                )
                ranked
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (t: Throwable) {
            run.failure("SEARCH", t)
            throw t
        }
    }

    private suspend fun loadShapeEmbeddings(): Map<Long, EmbeddingEntity> =
        database.embeddingDao().getAllForOwnerType(FaceShapeEncoder.OWNER_TYPE)
            .groupBy { it.ownerId }
            .mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }

    private suspend fun loadPersons(faces: List<FaceEntity>): Map<Long, PersonEntity> {
        val ids = faces.mapNotNull { it.personId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return database.personDao().getByIds(ids).associateBy { it.id }
    }

    private fun band(score: Float, identity: Float, quality: Float): ConfidenceBand = when {
        score >= 0.90f && identity >= 0.88f && quality >= 0.55f -> ConfidenceBand.VERY_HIGH
        score >= 0.82f && identity >= 0.78f && quality >= 0.40f -> ConfidenceBand.HIGH
        score >= 0.70f -> ConfidenceBand.MEDIUM
        else -> ConfidenceBand.LOW
    }

    override fun close() {
        try { analyzer.close() } catch (_: Throwable) { }
        try { embeddingModel.close() } catch (_: Throwable) { }
    }

    companion object {
        private const val FACE_MODEL_FILE = "models/face/mobilefacenet.tflite"
        private const val ACTIVE_MODEL = "mobilefacenet"
    }
}
