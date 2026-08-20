package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.vision.FaceNet512ModelManager
import com.example.personalmemoryai.vision.FileTFLiteFaceEmbeddingModel
import com.example.personalmemoryai.vision.MediaPipeFaceAnalyzer
import com.example.personalmemoryai.vision.TFLiteFaceEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaceIndexCoordinator(private val context: Context) : AutoCloseable {
    private val database = AppDatabase.getInstance(context)
    private val faceDao = database.faceDao()
    private val embeddingDao = database.embeddingDao()
    private val personDao = database.personDao()
    private val diagnostics = DiagnosticsManager.get(context)
    private val faceAnalyzerLazy = lazy { MediaPipeFaceAnalyzer(context) }
    private val embeddingModelLazy = lazy { TFLiteFaceEmbeddingModel(context, FACE_MODEL_FILE) }
    private val faceNetManager by lazy { FaceNet512ModelManager(context) }
    private val faceNetModelLazy = lazy {
        if (!faceNetManager.isInstalled()) null
        else FileTFLiteFaceEmbeddingModel(context, faceNetManager.modelFile, FileTFLiteFaceEmbeddingModel.Preprocessing.NEGATIVE_ONE_TO_ONE, FaceNet512ModelManager.EMBEDDING_DIMENSION)
    }
    private val analysisServiceLazy = lazy {
        val additional = faceNetModelLazy.value?.let { listOf(it) } ?: emptyList()
        com.example.personalmemoryai.vision.FaceAnalysisService(faceAnalyzerLazy.value, embeddingModelLazy.value, additional)
    }
    private val faceIndexingServiceLazy = lazy { FaceIndexingService(faceDao, embeddingDao, analysisServiceLazy.value, context) }
    private val clusteringEngineLazy = lazy { PersonClusteringEngine(faceDao, personDao, embeddingDao) }

    data class Progress(
        val processed: Int,
        val total: Int,
        val detectedFaces: Int,
        val indexedFaces: Int,
        val failedImages: Int,
        val modelEmbeddings: Map<String, Int> = emptyMap()
    )

    suspend fun indexSingleImage(imageId: Long, uri: Uri): FaceIndexingService.IndexResult = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("FACE_INDEX_SINGLE", mapOf("imageId" to imageId.toString(), "facenet512Installed" to faceNetManager.isInstalled().toString()))
        try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalStateException("تعذر فك الصورة الخاصة بتحليل الوجه: $uri")
            try {
                // Analyze first; old persisted evidence is removed only after the analysis succeeds.
                val result = faceIndexingServiceLazy.value.replaceImageIndex(imageId, bitmap)
                run.success("Single-image face index completed", mapOf("detected" to result.detectedFaces.toString(), "indexed" to result.indexedFaces.toString(), "models" to result.modelEmbeddings.entries.joinToString(",") { "${it.key}=${it.value}" }))
                result
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (t: Throwable) {
            run.failure("SINGLE_IMAGE", t)
            throw t
        }
    }

    suspend fun indexAllImages(onProgress: (Progress) -> Unit = {}): Progress = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("FACE_INDEX_ALL", mapOf("facenet512Installed" to faceNetManager.isInstalled().toString()))
        val images = database.imageDao().getAll()
        run.stage("LOAD", "Loaded images for face analysis", mapOf("total" to images.size.toString(), "facenet512Installed" to faceNetManager.isInstalled().toString()))
        var processed = 0
        var detected = 0
        var indexed = 0
        var failed = 0
        val modelCounts = linkedMapOf<String, Int>()
        val service = try { faceIndexingServiceLazy.value } catch (t: Throwable) { run.failure("MODEL_INIT", t); throw t }
        for (image in images) {
            try {
                val bitmap = decodeImage(Uri.parse(image.uri))
                if (bitmap == null) {
                    failed++
                    run.warning("IMAGE_DECODE", mapOf("imageId" to image.id.toString()))
                } else {
                    val result = service.replaceImageIndex(image.id, bitmap)
                    detected += result.detectedFaces
                    indexed += result.indexedFaces
                    result.modelEmbeddings.forEach { (name, count) -> modelCounts[name] = (modelCounts[name] ?: 0) + count }
                    if (result.indexedFaces == 0 && result.detectedFaces > 0) run.warning("NO_EMBEDDING", mapOf("imageId" to image.id.toString(), "detected" to result.detectedFaces.toString(), "modelErrors" to "inspect FACE_INDEX diagnostics"))
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            } catch (t: Throwable) {
                failed++
                run.failure("IMAGE_${image.id}", t)
            }
            processed++
            onProgress(Progress(processed, images.size, detected, indexed, failed, modelCounts.toMap()))
        }
        val result = Progress(processed, images.size, detected, indexed, failed, modelCounts.toMap())
        if (indexed == 0 && detected > 0) run.warning("ZERO_EMBEDDINGS", mapOf("detected" to detected.toString(), "models" to modelCounts.entries.joinToString(",")))
        run.success("Face index phase completed", mapOf("processed" to processed.toString(), "detected" to detected.toString(), "indexed" to indexed.toString(), "failed" to failed.toString(), "modelEmbeddings" to modelCounts.entries.joinToString(",") { "${it.key}=${it.value}" }))
        result
    }

    suspend fun buildPersonClusters(similarityThreshold: Float = DEFAULT_CLUSTER_THRESHOLD): PersonClusteringEngine.ClusterResult = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("PERSON_CLUSTERING", mapOf("threshold" to similarityThreshold.toString()))
        try {
            val result = clusteringEngineLazy.value.buildClusters(similarityThreshold)
            run.success("Person clustering completed", mapOf("clusters" to result.createdClusters.toString(), "assignedFaces" to result.assignedFaces.toString()))
            result
        } catch (t: Throwable) {
            run.failure("CLUSTERING", t)
            throw t
        }
    }

    suspend fun faceCount(): Long = withContext(Dispatchers.IO) { faceDao.count() }
    suspend fun embeddingCount(): Long = withContext(Dispatchers.IO) { faceDao.countWithEmbeddings() }
    private fun decodeImage(uri: Uri): android.graphics.Bitmap? = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    override fun close() { if (analysisServiceLazy.isInitialized()) try { analysisServiceLazy.value.close() } catch (_: Throwable) { } }
    companion object { private const val FACE_MODEL_FILE = "models/face/mobilefacenet.tflite"; private const val DEFAULT_CLUSTER_THRESHOLD = 0.72f }
}
