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

/**
 * Multi-signal face retrieval. It combines learned identity embeddings,
 * normalized facial geometry, estimated head pose and image quality.
 * Results are visual similarity evidence, not proof of real-world identity.
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
        val run = diagnostics.begin("FACE_SEARCH", mapOf("limit" to limit.toString()))
        try {
            val bitmap = context.contentResolver.openInputStream(queryUri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("تعذر قراءة صورة البحث عن الوجه")
            try {
                run.stage("DETECT_QUERY", "Detecting query faces, landmarks, pose and identity embeddings")
                val queries = FaceAnalysisService(analyzer, embeddingModel).analyze(bitmap)
                val usableQueries = queries.filter { it.embedding != null && it.landmarkShape != null && it.pose != null && it.usableForMatching }
                if (usableQueries.isEmpty()) error("لم يتم العثور على وجه قابل للمطابقة في صورة البحث")

                val storedFaces = database.faceDao().getMatchableFaces()
                val identityEmbeddings = database.embeddingDao().getAllForFaceSearch().associateBy { it.ownerId }
                val shapeEmbeddings = loadShapeEmbeddings()
                val imageIds = storedFaces.map { it.imageId }.distinct()
                val images = database.imageDao().getByIds(imageIds).associateBy { it.id }
                val persons = loadPersons(storedFaces)
                run.stage(
                    "LOAD_INDEX",
                    "Loaded multi-signal face index",
                    mapOf(
                        "faces" to storedFaces.size.toString(),
                        "identityEmbeddings" to identityEmbeddings.size.toString(),
                        "shapeEmbeddings" to shapeEmbeddings.size.toString()
                    )
                )

                val results = mutableListOf<FaceMatch>()
                for (stored in storedFaces) {
                    val identity = identityEmbeddings[stored.id] ?: continue
                    val shape = shapeEmbeddings[stored.id]
                    val image = images[stored.imageId] ?: continue
                    val person = persons[stored.personId]
                    val storedPose = FacePoseEstimator.Pose(stored.rotationX ?: 0f, stored.rotationY ?: 0f, stored.rotationZ ?: 0f)
                    for (query in usableQueries) {
                        val queryIdentity = query.embedding ?: continue
                        if (identity.vector.size != queryIdentity.size) continue

                        // Both vectors are normalized by the indexing pipeline; cosine is therefore
                        // used directly rather than remapping negative values into artificial confidence.
                        val identityCosine = FaceSimilarity.cosineSimilarity(queryIdentity, identity.vector)
                        val identity01 = identityCosine.coerceIn(0f, 1f)
                        val shapeSimilarity = if (shape != null && query.landmarkShape != null) {
                            FaceShapeEncoder.similarity(query.landmarkShape, shape.vector)
                        } else 0f
                        val poseSimilarity = FacePoseEstimator.similarity(query.pose, storedPose)

                        // Identity remains dominant; geometry and pose are independent corroborating signals.
                        val signalScore = (
                            0.70f * identity01 +
                            0.20f * shapeSimilarity +
                            0.10f * poseSimilarity
                        ).coerceIn(0f, 1f)
                        val storedQuality = stored.qualityScore.coerceIn(0f, 1f)
                        val jointQuality = (query.quality.score.coerceIn(0f, 1f) * storedQuality).let { kotlin.math.sqrt(it) }
                        val qualityFactor = 0.70f + 0.30f * jointQuality
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
                            confidenceBand = band(composite)
                        )
                    }
                }

                val ranked = results
                    .groupBy { it.face.id }
                    .values
                    .mapNotNull { candidates -> candidates.maxByOrNull { it.compositeScore } }
                    .sortedByDescending { it.compositeScore }
                    .take(limit)
                run.success(
                    "Face search completed",
                    mapOf(
                        "queryFaces" to usableQueries.size.toString(),
                        "candidates" to results.size.toString(),
                        "results" to ranked.size.toString()
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
        database.embeddingDao().getAllForOwnerType(FaceShapeEncoder.OWNER_TYPE).associateBy { it.ownerId }

    private suspend fun loadPersons(faces: List<FaceEntity>): Map<Long, PersonEntity> {
        val ids = faces.mapNotNull { it.personId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return database.personDao().getByIds(ids).associateBy { it.id }
    }

    private fun band(score: Float): ConfidenceBand = when {
        score >= 0.90f -> ConfidenceBand.VERY_HIGH
        score >= 0.82f -> ConfidenceBand.HIGH
        score >= 0.70f -> ConfidenceBand.MEDIUM
        else -> ConfidenceBand.LOW
    }

    override fun close() {
        try { analyzer.close() } catch (_: Throwable) { }
        try { embeddingModel.close() } catch (_: Throwable) { }
    }

    companion object {
        private const val FACE_MODEL_FILE = "models/face/mobilefacenet.tflite"
    }
}
