package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.semantic.SemanticSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single orchestration layer for the complete local intelligence pipeline.
 * It keeps OCR/objects, faces and optional visual embeddings synchronized and
 * records every stage in Diagnostics so a partial failure is visible.
 */
class FullIndexingCoordinator(private val context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val imageIndexer = ImageIndexer(appContext)
    private val faceCoordinator = FaceIndexCoordinator(appContext)
    private val semantic = SemanticSearchService(appContext)
    private val diagnostics = DiagnosticsManager.get(appContext)

    data class Progress(
        val processed: Int,
        val total: Int,
        val imagesIndexed: Int,
        val imageFailures: Int,
        val facesDetected: Int,
        val facesIndexed: Int,
        val faceFailures: Int,
        val visualEmbedded: Int,
        val visualSkipped: Int,
        val visualFailures: Int
    )

    suspend fun indexUris(
        uris: List<Uri>,
        includeVisual: Boolean = semantic.isModelInstalled(),
        onProgress: (Progress) -> Unit = {}
    ): Progress = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("FULL_INDEX", mapOf("total" to uris.size.toString(), "visual" to includeVisual.toString()))
        var processed = 0
        var imagesIndexed = 0
        var imageFailures = 0
        var facesDetected = 0
        var facesIndexed = 0
        var faceFailures = 0
        var visualEmbedded = 0
        var visualSkipped = 0
        var visualFailures = 0

        run.stage("PIPELINE", "Starting complete image intelligence pipeline")
        for (uri in uris.distinct()) {
            var imageId: Long? = null
            try {
                run.stage("IMAGE", "Indexing OCR, metadata and objects", mapOf("uri" to uri.toString()))
                val entity = imageIndexer.indexImage(uri)
                if (entity == null) {
                    imageFailures++
                } else {
                    imagesIndexed++
                    imageId = entity.id
                    try {
                        val result = faceCoordinator.indexSingleImage(entity.id, Uri.parse(entity.uri))
                        facesDetected += result.detectedFaces
                        facesIndexed += result.indexedFaces
                        if (result.detectedFaces > 0 && result.indexedFaces == 0) faceFailures++
                    } catch (t: Throwable) {
                        faceFailures++
                        run.failure("FACE_IMAGE_${entity.id}", t)
                    }

                    if (includeVisual) {
                        try {
                            val existing = db.embeddingDao().getForOwnerAndModel(
                                "IMAGE", entity.id,
                                com.example.personalmemoryai.semantic.MobileClipImageEncoder.MODEL_NAME,
                                com.example.personalmemoryai.semantic.MobileClipImageEncoder.MODEL_VERSION
                            )
                            if (existing != null && existing.vector.isNotEmpty()) {
                                visualSkipped++
                            } else {
                                semantic.indexImageAndStore(entity)
                                visualEmbedded++
                            }
                        } catch (t: Throwable) {
                            visualFailures++
                            run.failure("VISUAL_IMAGE_${entity.id}", t)
                        }
                    }
                }
            } catch (t: Throwable) {
                imageFailures++
                run.failure("IMAGE_PIPELINE_${imageId ?: "NEW"}", t)
            } finally {
                processed++
                onProgress(Progress(processed, uris.size, imagesIndexed, imageFailures, facesDetected, facesIndexed, faceFailures, visualEmbedded, visualSkipped, visualFailures))
            }
        }

        if (facesIndexed > 0) {
            try { faceCoordinator.buildPersonClusters() }
            catch (t: Throwable) { run.failure("PERSON_CLUSTERING", t) }
        }
        val result = Progress(processed, uris.size, imagesIndexed, imageFailures, facesDetected, facesIndexed, faceFailures, visualEmbedded, visualSkipped, visualFailures)
        run.success("Complete indexing finished", mapOf(
            "imagesIndexed" to imagesIndexed.toString(),
            "imageFailures" to imageFailures.toString(),
            "facesDetected" to facesDetected.toString(),
            "facesIndexed" to facesIndexed.toString(),
            "faceFailures" to faceFailures.toString(),
            "visualEmbedded" to visualEmbedded.toString(),
            "visualSkipped" to visualSkipped.toString(),
            "visualFailures" to visualFailures.toString()
        ))
        result
    }

    override fun close() {
        try { semantic.close() } catch (_: Throwable) {}
        try { faceCoordinator.close() } catch (_: Throwable) {}
        try { imageIndexer.close() } catch (_: Throwable) {}
    }
}
