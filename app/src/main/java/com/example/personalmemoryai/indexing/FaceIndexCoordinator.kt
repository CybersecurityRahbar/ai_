package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.vision.FaceAnalysisService
import com.example.personalmemoryai.vision.MediaPipeFaceAnalyzer
import com.example.personalmemoryai.vision.TFLiteFaceEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs the complete face indexing phase with observable failure accounting. */
class FaceIndexCoordinator(private val context: Context) : AutoCloseable {
    private val database = AppDatabase.getInstance(context)
    private val faceDao = database.faceDao()
    private val embeddingDao = database.embeddingDao()
    private val personDao = database.personDao()
    private val diagnostics = DiagnosticsManager.get(context)
    private val faceAnalyzerLazy = lazy { MediaPipeFaceAnalyzer(context) }
    private val embeddingModelLazy = lazy { TFLiteFaceEmbeddingModel(context, FACE_MODEL_FILE) }
    private val analysisServiceLazy = lazy { FaceAnalysisService(faceAnalyzerLazy.value, embeddingModelLazy.value) }
    private val faceIndexingServiceLazy = lazy { FaceIndexingService(faceDao, embeddingDao, analysisServiceLazy.value, context) }
    private val clusteringEngineLazy = lazy { PersonClusteringEngine(faceDao, personDao, embeddingDao) }

    data class Progress(val processed: Int, val total: Int, val detectedFaces: Int, val indexedFaces: Int, val failedImages: Int)

    suspend fun indexAllImages(onProgress: (Progress) -> Unit = {}): Progress = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("FACE_INDEX_ALL")
        val images = database.imageDao().getAll()
        run.stage("LOAD", "Loaded images for face analysis", mapOf("total" to images.size.toString()))
        var processed = 0
        var detected = 0
        var indexed = 0
        var failed = 0
        val service = try { faceIndexingServiceLazy.value } catch (t: Throwable) {
            run.failure("MODEL_INIT", t)
            throw t
        }
        for (image in images) {
            try {
                val bitmap = decodeImage(Uri.parse(image.uri))
                if (bitmap == null) {
                    failed++
                    run.warning("IMAGE_DECODE", mapOf("imageId" to image.id.toString()))
                } else {
                    service.removeImageIndex(image.id)
                    val result = service.indexImage(image.id, bitmap)
                    detected += result.detectedFaces
                    indexed += result.indexedFaces
                    if (result.indexedFaces == 0 && result.detectedFaces > 0) run.warning("NO_EMBEDDING", mapOf("imageId" to image.id.toString(), "detected" to result.detectedFaces.toString()))
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            } catch (t: Throwable) {
                failed++
                run.failure("IMAGE_${image.id}", t)
            }
            processed++
            onProgress(Progress(processed, images.size, detected, indexed, failed))
        }
        val result = Progress(processed, images.size, detected, indexed, failed)
        if (indexed == 0 && detected > 0) run.warning("ZERO_EMBEDDINGS", mapOf("detected" to detected.toString()))
        run.success("Face index phase completed", mapOf("processed" to processed.toString(), "detected" to detected.toString(), "indexed" to indexed.toString(), "failed" to failed.toString()))
        result
    }

    suspend fun buildPersonClusters(similarityThreshold: Float = DEFAULT_CLUSTER_THRESHOLD): PersonClusteringEngine.ClusterResult = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("PERSON_CLUSTERING", mapOf("threshold" to similarityThreshold.toString()))
        try {
            val result = clusteringEngineLazy.value.buildClusters(similarityThreshold)
            run.success("Person clustering completed", mapOf("clusters" to result.clusterCount.toString()))
            result
        } catch (t: Throwable) {
            run.failure("CLUSTERING", t)
            throw t
        }
    }

    suspend fun faceCount(): Long = withContext(Dispatchers.IO) { faceDao.count() }
    suspend fun embeddingCount(): Long = withContext(Dispatchers.IO) { faceDao.countWithEmbeddings() }

    private fun decodeImage(uri: Uri): Bitmap? = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

    override fun close() {
        if (analysisServiceLazy.isInitialized()) try { analysisServiceLazy.value.close() } catch (_: Throwable) { }
    }

    companion object {
        private const val FACE_MODEL_FILE = "models/face/mobilefacenet.tflite"
        private const val DEFAULT_CLUSTER_THRESHOLD = 0.72f
    }
}
