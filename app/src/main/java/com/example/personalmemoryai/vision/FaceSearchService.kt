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

/** Multi-signal face retrieval using every installed identity model plus geometry, pose and quality. */
class FaceSearchService(private val context: Context) : AutoCloseable {
    private val database = AppDatabase.getInstance(context.applicationContext)
    private val diagnostics = DiagnosticsManager.get(context)
    private val analyzer by lazy { MediaPipeFaceAnalyzer(context.applicationContext) }
    private val mobileModel by lazy { TFLiteFaceEmbeddingModel(context.applicationContext, MOBILE_MODEL_FILE) }
    private val faceNetManager by lazy { FaceNet512ModelManager(context.applicationContext) }
    private val faceNetModel by lazy {
        if (!faceNetManager.isInstalled()) null
        else FileTFLiteFaceEmbeddingModel(
            context.applicationContext,
            faceNetManager.modelFile,
            FileTFLiteFaceEmbeddingModel.Preprocessing.NEGATIVE_ONE_TO_ONE,
            FaceNet512ModelManager.EMBEDDING_DIMENSION
        )
    }

    data class FaceMatch(
        val image: ImageEntity,
        val face: FaceEntity,
        val person: PersonEntity?,
        val identitySimilarity: Float,
        val faceNet512Similarity: Float?,
        val shapeSimilarity: Float,
        val poseSimilarity: Float,
        val quality: Float,
        val compositeScore: Float,
        val confidenceBand: ConfidenceBand,
        val modelEvidence: Map<String, Float>
    )

    enum class ConfidenceBand { VERY_HIGH, HIGH, MEDIUM, LOW }

    suspend fun search(queryUri: Uri, limit: Int = 30): List<FaceMatch> = withContext(Dispatchers.Default) {
        val installed = faceNetManager.isInstalled()
        val run = diagnostics.begin("FACE_SEARCH", mapOf("limit" to limit.toString(), "mobileModel" to "mobilefacenet", "facenet512Installed" to installed.toString()))
        try {
            val bitmap = context.contentResolver.openInputStream(queryUri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("تعذر قراءة صورة البحث عن الوجه")
            try {
                run.stage("DETECT_QUERY", "Detecting query faces and generating all installed identity embeddings")
                val models = listOf<FaceEmbeddingModel>(mobileModel) + listOfNotNull(faceNetModel)
                val analysis = FaceAnalysisService(analyzer, models.first(), models.drop(1))
                val queries = analysis.analyze(bitmap)
                val usableQueries = queries.filter { it.embeddings.isNotEmpty() && it.landmarkShape != null && it.pose != null && it.usableForMatching }
                if (usableQueries.isEmpty()) error("لم يتم العثور على وجه قابل للمطابقة في صورة البحث")

                val storedFaces = database.faceDao().getMatchableFaces()
                val allEmbeddings = database.embeddingDao().getAllForFaceSearch()
                val embeddingsByModelAndFace = allEmbeddings
                    .groupBy { it.modelName.lowercase() }
                    .mapValues { (_, values) -> values.groupBy { it.ownerId }.mapValues { (_, list) -> list.maxByOrNull { it.createdAt }!! } }
                val shapeEmbeddings = loadShapeEmbeddings()
                val imageIds = storedFaces.map { it.imageId }.distinct()
                val images = database.imageDao().getByIds(imageIds).associateBy { it.id }
                val persons = loadPersons(storedFaces)

                run.stage("LOAD_INDEX", "Loaded multi-model face index", mapOf(
                    "faces" to storedFaces.size.toString(),
                    "mobilefacenet" to (embeddingsByModelAndFace["mobilefacenet"]?.size ?: 0).toString(),
                    "facenet_512" to (embeddingsByModelAndFace["facenet_512"]?.size ?: 0).toString(),
                    "shapeEmbeddings" to shapeEmbeddings.size.toString()
                ))

                val results = mutableListOf<FaceMatch>()
                for (stored in storedFaces) {
                    val image = images[stored.imageId] ?: continue
                    val person = stored.personId?.let { persons[it] }
                    val shape = shapeEmbeddings[stored.id]
                    val storedPose = FacePoseEstimator.Pose(stored.rotationX ?: 0f, stored.rotationY ?: 0f, stored.rotationZ ?: 0f)
                    val storedModelScores = linkedMapOf<String, Float>()
                    for (query in usableQueries) {
                        val queryEmbeddings = query.embeddings
                        for ((modelName, queryModel) in queryEmbeddings) {
                            val storedEmbedding = embeddingsByModelAndFace[modelName.lowercase()]?.get(stored.id) ?: continue
                            if (storedEmbedding.vector.size != queryModel.vector.size) continue
                            storedModelScores[modelName.lowercase()] = FaceSimilarity.cosineSimilarity(queryModel.vector, storedEmbedding.vector).coerceIn(-1f, 1f)
                        }
                        if (storedModelScores.isEmpty()) continue

                        val mobile = storedModelScores["mobilefacenet"]?.let { ((it + 1f) / 2f).coerceIn(0f, 1f) }
                        val faceNet = storedModelScores["facenet_512"]?.let { ((it + 1f) / 2f).coerceIn(0f, 1f) }
                        val identity = when {
                            mobile != null && faceNet != null -> 0.45f * mobile + 0.55f * faceNet
                            faceNet != null -> faceNet
                            else -> mobile ?: 0f
                        }
                        val shapeSimilarity = if (shape != null && query.landmarkShape != null) FaceShapeEncoder.similarity(query.landmarkShape, shape.vector) else 0f
                        val poseSimilarity = FacePoseEstimator.similarity(query.pose, storedPose)
                        val storedQuality = stored.qualityScore.coerceIn(0f, 1f)
                        val queryQuality = query.quality.score.coerceIn(0f, 1f)
                        val jointQuality = sqrt((queryQuality * storedQuality).coerceIn(0f, 1f))
                        val modelCount = if (mobile != null && faceNet != null) 2f else 1f
                        val modelAgreement = if (mobile != null && faceNet != null) {
                            (1f - kotlin.math.abs(mobile - faceNet)).coerceIn(0f, 1f)
                        } else 0.75f
                        val signalScore = (
                            0.72f * identity +
                            0.15f * shapeSimilarity +
                            0.08f * poseSimilarity +
                            0.05f * modelAgreement
                        ).coerceIn(0f, 1f)
                        val qualityFactor = 0.68f + 0.32f * jointQuality
                        val modelCoverage = if (modelCount == 2f) 1f else 0.93f
                        val composite = (signalScore * qualityFactor * modelCoverage).coerceIn(0f, 1f)

                        results += FaceMatch(
                            image, stored, person,
                            identity,
                            faceNet,
                            shapeSimilarity,
                            poseSimilarity,
                            jointQuality,
                            composite,
                            band(composite, identity, jointQuality, modelCount),
                            storedModelScores.mapValues { ((it.value + 1f) / 2f).coerceIn(0f, 1f) }
                        )
                    }
                }

                val ranked = results.groupBy { it.face.id }.values
                    .mapNotNull { it.maxByOrNull { match -> match.compositeScore } }
                    .sortedWith(compareByDescending<FaceMatch> { it.compositeScore }.thenByDescending { it.identitySimilarity })
                    .take(limit)

                run.success("Face search completed", mapOf(
                    "queryFaces" to usableQueries.size.toString(),
                    "candidates" to results.size.toString(),
                    "results" to ranked.size.toString(),
                    "facenet512Used" to faceNetModel?.let { "true" } .toString()
                ))
                ranked
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (t: Throwable) {
            run.failure("SEARCH", t)
            throw t
        }
    }

    private suspend fun loadShapeEmbeddings(): Map<Long, EmbeddingEntity> = database.embeddingDao().getAllForOwnerType(FaceShapeEncoder.OWNER_TYPE)
        .groupBy { it.ownerId }.mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }

    private suspend fun loadPersons(faces: List<FaceEntity>): Map<Long, PersonEntity> {
        val ids = faces.mapNotNull { it.personId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return database.personDao().getByIds(ids).associateBy { it.id }
    }

    private fun band(score: Float, identity: Float, quality: Float, modelCount: Float): ConfidenceBand = when {
        score >= 0.90f && identity >= 0.88f && quality >= 0.55f && modelCount >= 2f -> ConfidenceBand.VERY_HIGH
        score >= 0.82f && identity >= 0.78f && quality >= 0.40f -> ConfidenceBand.HIGH
        score >= 0.70f -> ConfidenceBand.MEDIUM
        else -> ConfidenceBand.LOW
    }

    override fun close() {
        try { analyzer.close() } catch (_: Throwable) { }
        try { mobileModel.close() } catch (_: Throwable) { }
        try { faceNetModel?.close() } catch (_: Throwable) { }
    }

    companion object { private const val MOBILE_MODEL_FILE = "models/face/mobilefacenet.tflite" }
}
