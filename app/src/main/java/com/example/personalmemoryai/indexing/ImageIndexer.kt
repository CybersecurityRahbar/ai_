package com.example.personalmemoryai.indexing

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.personalmemoryai.data.ManagedImageStore
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.database.ObjectEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.semantic.SemanticSearchService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Complete per-image intelligence pipeline with explicit stage health and failure accounting. */
class ImageIndexer(private val context: Context) : AutoCloseable {
    private val database = AppDatabase.getInstance(context)
    private val dao = database.imageDao()
    private val objectDao = database.objectDao()
    private val ocrEngine = OcrEngine(context)
    private val objectDetector = YoloObjectDetector(context)
    private val imageStore = ManagedImageStore(context)
    private val diagnostics = DiagnosticsManager.get(context)
    private val faceCoordinator = FaceIndexCoordinator(context)
    private val semanticSearchService = SemanticSearchService(context)

    suspend fun indexImage(uri: Uri): ImageEntity? {
        val run = diagnostics.begin("IMAGE_INDEX", mapOf("uri" to uri.toString()))
        return try {
            run.stage("METADATA", "Reading image metadata")
            val sourceUri = uri.toString()
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED)
            var fileName = "Unknown"; var fileSize = 0L; var width = 0; var height = 0; var mimeType: String? = null; var dateTaken: Long? = null; var dateModified: Long? = null
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME).takeIf { it >= 0 }?.let { fileName = cursor.getString(it) ?: "Unknown" }
                    cursor.getColumnIndex(MediaStore.Images.Media.SIZE).takeIf { it >= 0 }?.let { fileSize = cursor.getLong(it) }
                    cursor.getColumnIndex(MediaStore.Images.Media.WIDTH).takeIf { it >= 0 }?.let { width = cursor.getInt(it) }
                    cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT).takeIf { it >= 0 }?.let { height = cursor.getInt(it) }
                    cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE).takeIf { it >= 0 }?.let { mimeType = cursor.getString(it) }
                    cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN).takeIf { it >= 0 }?.let { dateTaken = cursor.getLong(it) }
                    cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED).takeIf { it >= 0 }?.let { dateModified = cursor.getLong(it) * 1000 }
                }
            } ?: run.warning("MediaStore metadata query returned no cursor")

            val existing = dao.findBySourceUri(sourceUri) ?: dao.findByUri(sourceUri)
            if (existing != null) {
                run.warning("Image already indexed", mapOf("imageId" to existing.id.toString()))
                return existing
            }

            run.stage("OCR", "Running multi-pass OCR pipeline")
            val ocr = try { ocrEngine.process(uri) } catch (t: Throwable) {
                run.failure("OCR", t)
                OcrResult("", "none", 0f, 0, 0, 0, 0)
            }
            run.stage("OCR_RESULT", "OCR evidence evaluated", mapOf(
                "characters" to ocr.text.length.toString(), "language" to ocr.language,
                "quality" to "%.3f".format(java.util.Locale.US, ocr.qualityScore), "passes" to ocr.passCount.toString(),
                "successfulPasses" to ocr.successfulPasses.toString(), "arabicCharacters" to ocr.arabicCharacters.toString(),
                "latinCharacters" to ocr.latinCharacters.toString()
            ))
            if (ocr.text.isBlank()) run.warning("OCR produced no text; this is not treated as a successful extraction")

            run.stage("OBJECTS", "Running YOLO object detector")
            val objectStarted = System.nanoTime()
            var objectFailed = false
            val objects = try { runObjectDetection(uri) } catch (t: Throwable) {
                objectFailed = true
                run.failure("OBJECTS", t, mapOf("detector" to "YOLO26n W8A32", "sourceUri" to sourceUri))
                emptyList()
            }
            val objectInferenceMs = (System.nanoTime() - objectStarted) / 1_000_000L
            run.stage("OBJECT_RESULT", "Object detection completed", mapOf("detections" to objects.size.toString(), "inferenceMs" to objectInferenceMs.toString(), "detector" to "YOLO26n W8A32", "failed" to objectFailed.toString()))

            run.stage("IMAGE_STORE", "Copying image into managed knowledge storage")
            val managedFile = imageStore.importImage(uri, fileName)
            val managedUri = managedFile?.let { Uri.fromFile(it).toString() } ?: sourceUri
            if (managedFile == null) run.warning("Managed image copy unavailable; source URI retained")

            val entity = ImageEntity(
                uri = managedUri, fileName = fileName, filePath = sourceUri, dateTaken = dateTaken, dateModified = dateModified,
                fileSize = fileSize, width = width, height = height, mimeType = mimeType, ocrText = ocr.text, ocrLanguage = ocr.language,
                ocrQualityScore = ocr.qualityScore, ocrPassCount = ocr.passCount, ocrSuccessfulPasses = ocr.successfulPasses,
                ocrLatinCharacters = ocr.latinCharacters, ocrArabicCharacters = ocr.arabicCharacters,
                detectedObjects = serializeObjects(objects), indexedAt = System.currentTimeMillis()
            )
            run.stage("DATABASE", "Persisting indexed image")
            val id = dao.insert(entity)
            val result = entity.copy(id = id)

            run.stage("OBJECT_PERSISTENCE", "Persisting object observations")
            try {
                objectDao.deleteForImage(id)
                if (objects.isNotEmpty()) objectDao.insertAll(objects.map { detection ->
                    ObjectEntity(imageId = id, classId = detection.classId, label = detection.label, arabicLabel = detection.arabicLabel, confidence = detection.confidence, left = detection.left, top = detection.top, right = detection.right, bottom = detection.bottom, detectorName = "YOLO26n W8A32", detectorVersion = "1", inferenceTimeMs = objectInferenceMs, createdAt = System.currentTimeMillis())
                })
                run.stage("OBJECT_PERSISTENCE_RESULT", "Object observations persisted", mapOf("rows" to objects.size.toString()))
            } catch (t: Throwable) { run.failure("OBJECT_PERSISTENCE", t, mapOf("imageId" to id.toString())) }

            var faceFailed = false
            var visualFailed = false
            var visualSkipped = false
            coroutineScope {
                val faceJob = async {
                    try {
                        run.stage("FACES", "Running MediaPipe landmarks + face embeddings", mapOf("imageId" to id.toString()))
                        val faceResult = faceCoordinator.indexSingleImage(id, Uri.parse(result.uri))
                        run.stage("FACES_RESULT", "Face analysis completed", mapOf("imageId" to id.toString(), "detected" to faceResult.detectedFaces.toString(), "embeddings" to faceResult.indexedFaces.toString()))
                    } catch (t: Throwable) {
                        faceFailed = true
                        run.failure("FACES", t, mapOf("imageId" to id.toString(), "sourceUri" to sourceUri))
                    }
                }
                val visualJob = if (semanticSearchService.isModelInstalled()) async {
                    try {
                        run.stage("VISUAL", "Running MobileCLIP-S2 visual embedding", mapOf("imageId" to id.toString()))
                        val embedding = semanticSearchService.indexImageAndStore(result)
                        run.stage("VISUAL_RESULT", "Visual embedding persisted", mapOf("imageId" to id.toString(), "embeddingId" to embedding.toString()))
                    } catch (t: Throwable) {
                        visualFailed = true
                        run.failure("VISUAL", t, mapOf("imageId" to id.toString(), "sourceUri" to sourceUri))
                    }
                } else {
                    visualSkipped = true
                    run.warning("VISUAL_SKIPPED", "MobileCLIP-S2 is not installed or not validated", mapOf("imageId" to id.toString()))
                    null
                }
                faceJob.await(); visualJob?.await()
            }

            val degraded = objectFailed || faceFailed || visualFailed || visualSkipped
            run.stage("PIPELINE_HEALTH", "Per-image intelligence health evaluated", mapOf(
                "imageId" to id.toString(), "ocr" to if (ocr.text.isNotBlank()) "SUCCESS" else "NO_TEXT",
                "objects" to if (objectFailed) "FAILED" else "SUCCESS",
                "faces" to if (faceFailed) "FAILED" else "SUCCESS",
                "visual" to when { visualFailed -> "FAILED"; visualSkipped -> "NOT_READY"; else -> "SUCCESS" },
                "overall" to if (degraded) "DEGRADED" else "SUCCESS"
            ))

            if (degraded) {
                run.warning("Image indexed with degraded intelligence coverage", mapOf("imageId" to id.toString(), "objectFailed" to objectFailed.toString(), "faceFailed" to faceFailed.toString(), "visualFailed" to visualFailed.toString(), "visualSkipped" to visualSkipped.toString()))
            } else {
                run.success("Complete image intelligence indexed", mapOf(
                    "imageId" to id.toString(), "ocrChars" to ocr.text.length.toString(), "ocrQuality" to "%.3f".format(java.util.Locale.US, ocr.qualityScore),
                    "ocrArabicChars" to ocr.arabicCharacters.toString(), "objects" to objects.size.toString(), "objectInferenceMs" to objectInferenceMs.toString(),
                    "visualModelInstalled" to semanticSearchService.isModelInstalled().toString()
                ))
            }
            result
        } catch (t: Throwable) { run.failure("PIPELINE", t, mapOf("uri" to uri.toString())); null }
    }

    private fun runObjectDetection(uri: Uri): List<ObjectDetectionResult> = objectDetector.detect(uri)

    private fun serializeObjects(objects: List<ObjectDetectionResult>): String = objects.groupBy { it.classId }.values.joinToString("; ") { detections ->
        val best = detections.maxByOrNull { it.confidence } ?: return@joinToString ""
        if (best.arabicLabel.isBlank()) "${best.label}:${"%.3f".format(java.util.Locale.US, best.confidence)}" else "${best.label}|${best.arabicLabel}:${"%.3f".format(java.util.Locale.US, best.confidence)}"
    }

    override fun close() { ocrEngine.close(); objectDetector.close(); faceCoordinator.close(); semanticSearchService.close() }
}
