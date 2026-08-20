package com.example.personalmemoryai.indexing

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.semantic.MobileClipImageEncoder
import com.example.personalmemoryai.semantic.MobileClipModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Batch orchestration/reporting layer for the complete local image pipeline.
 *
 * IMPORTANT: ImageIndexer is the single owner of per-image OCR, object,
 * face and optional MobileCLIP execution. This coordinator must never invoke
 * those engines a second time. It only batches images, measures persisted
 * results and performs the final person-cluster rebuild.
 */
class FullIndexingCoordinator(private val context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val imageIndexer = ImageIndexer(appContext)
    private val clusterCoordinator = FaceIndexCoordinator(appContext)
    private val modelManager = MobileClipModelManager(appContext)
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
        includeVisual: Boolean = modelManager.isInstalled(),
        onProgress: (Progress) -> Unit = {}
    ): Progress = withContext(Dispatchers.IO) {
        val uniqueUris = uris.distinct()
        val run = diagnostics.begin("FULL_INDEX", mapOf("total" to uniqueUris.size.toString(), "visual" to includeVisual.toString()))
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

        for (uri in uniqueUris) {
            var imageId: Long? = null
            try {
                val existingBefore = db.imageDao().findBySourceUri(uri.toString()) ?: db.imageDao().findByUri(uri.toString())
                val existingFaceCount = existingBefore?.let { db.faceDao().getByImageId(it.id).size } ?: 0
                val existingVisual = existingBefore?.let {
                    db.embeddingDao().getForOwnerAndModel(
                        MobileClipImageEncoder.OWNER_TYPE,
                        it.id,
                        MobileClipImageEncoder.MODEL_NAME,
                        MobileClipImageEncoder.MODEL_VERSION
                    )
                }

                run.stage("IMAGE", "Delegating per-image OCR, objects, faces and visual indexing", mapOf("uri" to uri.toString()))
                val entity = imageIndexer.indexImage(uri)
                if (entity == null) {
                    imageFailures++
                } else {
                    imagesIndexed++
                    imageId = entity.id

                    val persistedFaces = db.faceDao().getByImageId(entity.id).size
                    facesDetected += persistedFaces
                    facesIndexed += persistedFaces
                    if (existingBefore != null && persistedFaces == existingFaceCount) {
                        run.stage("FACES_REUSED", "Existing face evidence retained", mapOf("imageId" to entity.id.toString(), "faces" to persistedFaces.toString()))
                    }

                    val persistedVisual = db.embeddingDao().getForOwnerAndModel(
                        MobileClipImageEncoder.OWNER_TYPE,
                        entity.id,
                        MobileClipImageEncoder.MODEL_NAME,
                        MobileClipImageEncoder.MODEL_VERSION
                    )
                    when {
                        persistedVisual != null && persistedVisual.vector.isNotEmpty() -> {
                            if (existingVisual != null) visualSkipped++ else visualEmbedded++
                        }
                        includeVisual -> {
                            visualFailures++
                            run.warning("VISUAL_NOT_PERSISTED", mapOf("imageId" to entity.id.toString()))
                        }
                        else -> visualSkipped++
                    }

                    run.stage("IMAGE_RESULT", "Per-image pipeline completed", mapOf(
                        "imageId" to entity.id.toString(),
                        "faces" to persistedFaces.toString(),
                        "visual" to if (persistedVisual != null) "PERSISTED" else if (includeVisual) "FAILED" else "NOT_READY"
                    ))
                }
            } catch (t: Throwable) {
                imageFailures++
                run.failure("IMAGE_PIPELINE_${imageId ?: "NEW"}", t)
            } finally {
                processed++
                onProgress(Progress(
                    processed = processed,
                    total = uniqueUris.size,
                    imagesIndexed = imagesIndexed,
                    imageFailures = imageFailures,
                    facesDetected = facesDetected,
                    facesIndexed = facesIndexed,
                    faceFailures = faceFailures,
                    visualEmbedded = visualEmbedded,
                    visualSkipped = visualSkipped,
                    visualFailures = visualFailures
                ))
            }
        }

        if (facesIndexed > 0) {
            try {
                val clusters = clusterCoordinator.buildPersonClusters()
                run.stage("PERSON_CLUSTERING", "Person clusters rebuilt from persisted face evidence", mapOf(
                    "createdClusters" to clusters.createdClusters.toString(),
                    "assignedFaces" to clusters.assignedFaces.toString()
                ))
            } catch (t: Throwable) {
                faceFailures++
                run.failure("PERSON_CLUSTERING", t)
            }
        }

        val result = Progress(processed, uniqueUris.size, imagesIndexed, imageFailures, facesDetected, facesIndexed, faceFailures, visualEmbedded, visualSkipped, visualFailures)
        val totalFailures = imageFailures + faceFailures + visualFailures
        if (totalFailures == 0) {
            run.success("Complete indexing finished", mapOf(
                "imagesIndexed" to imagesIndexed.toString(),
                "facesIndexed" to facesIndexed.toString(),
                "visualEmbedded" to visualEmbedded.toString(),
                "visualSkipped" to visualSkipped.toString()
            ))
        } else {
            run.warning("Complete indexing finished with degraded stages", mapOf(
                "imageFailures" to imageFailures.toString(),
                "faceFailures" to faceFailures.toString(),
                "visualFailures" to visualFailures.toString(),
                "visualSkipped" to visualSkipped.toString()
            ))
        }
        result
    }

    override fun close() {
        try { clusterCoordinator.close() } catch (_: Throwable) {}
        try { imageIndexer.close() } catch (_: Throwable) {}
    }
}
