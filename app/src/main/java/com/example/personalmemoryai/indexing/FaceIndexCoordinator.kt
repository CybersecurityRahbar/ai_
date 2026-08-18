package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.vision.FaceAnalysisService
import com.example.personalmemoryai.vision.MediaPipeFaceAnalyzer
import com.example.personalmemoryai.vision.TFLiteFaceEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the face pipeline as a separate indexing phase.
 *
 * Face indexing is intentionally NOT part of the basic OCR/YOLO image import
 * path. This keeps normal image indexing fast and lets the user run the more
 * expensive face analysis when desired.
 */
class FaceIndexCoordinator(
    private val context: Context
) : AutoCloseable {

    private val database = AppDatabase.getInstance(context)
    private val faceDao = database.faceDao()
    private val embeddingDao = database.embeddingDao()
    private val personDao = database.personDao()

    private val faceAnalyzer = MediaPipeFaceAnalyzer(context)
    private val embeddingModel = TFLiteFaceEmbeddingModel(
        context = context,
        modelFileName = FACE_MODEL_FILE
    )
    private val analysisService = FaceAnalysisService(
        faceAnalyzer = faceAnalyzer,
        embeddingModel = embeddingModel
    )
    private val faceIndexingService = FaceIndexingService(
        faceDao = faceDao,
        embeddingDao = embeddingDao,
        analysisService = analysisService
    )
    private val clusteringEngine = PersonClusteringEngine(
        faceDao = faceDao,
        personDao = personDao,
        embeddingDao = embeddingDao
    )

    data class Progress(
        val processed: Int,
        val total: Int,
        val detectedFaces: Int,
        val indexedFaces: Int,
        val failedImages: Int
    )

    suspend fun indexAllImages(
        onProgress: (Progress) -> Unit = {}
    ): Progress = withContext(Dispatchers.IO) {
        val images = database.imageDao().getAll()
        var processed = 0
        var detected = 0
        var indexed = 0
        var failed = 0

        for (image in images) {
            try {
                val bitmap = decodeImage(Uri.parse(image.uri))
                if (bitmap == null) {
                    failed++
                } else {
                    // Re-running face indexing must not create duplicate face rows.
                    faceIndexingService.removeImageIndex(image.id)
                    val result = faceIndexingService.indexImage(image.id, bitmap)
                    detected += result.detectedFaces
                    indexed += result.indexedFaces
                    bitmap.recycle()
                }
            } catch (_: Throwable) {
                failed++
            }

            processed++
            onProgress(
                Progress(
                    processed = processed,
                    total = images.size,
                    detectedFaces = detected,
                    indexedFaces = indexed,
                    failedImages = failed
                )
            )
        }

        Progress(
            processed = processed,
            total = images.size,
            detectedFaces = detected,
            indexedFaces = indexed,
            failedImages = failed
        )
    }

    suspend fun buildPersonClusters(
        similarityThreshold: Float = DEFAULT_CLUSTER_THRESHOLD
    ): PersonClusteringEngine.ClusterResult {
        return clusteringEngine.buildClusters(similarityThreshold)
    }

    suspend fun faceCount(): Long = withContext(Dispatchers.IO) {
        faceDao.count()
    }

    suspend fun embeddingCount(): Long = withContext(Dispatchers.IO) {
        faceDao.countWithEmbeddings()
    }

    private fun decodeImage(uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    override fun close() {
        analysisService.close()
    }

    companion object {
        private const val FACE_MODEL_FILE = "models/face/mobilefacenet.tflite"
        private const val DEFAULT_CLUSTER_THRESHOLD = 0.72f
    }
}
