package com.example.personalmemoryai.indexing

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.semantic.SemanticSearchService

class ImageIndexer(
    private val context: Context
) : AutoCloseable {

    private val database = AppDatabase.getInstance(context)
    private val dao = database.imageDao()
    private val ocrEngine = OcrEngine(context)
    private val objectDetector = YoloObjectDetector(context)
    private val semanticSearchService = SemanticSearchService(context)

    suspend fun indexImage(uri: Uri): ImageEntity? {
        return try {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED
            )

            var fileName = "Unknown"
            var fileSize = 0L
            var width = 0
            var height = 0
            var mimeType: String? = null
            var dateTaken: Long? = null
            var dateModified: Long? = null

            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fun index(name: String): Int = cursor.getColumnIndex(name)
                    cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                        fileName = cursor.getString(it) ?: "Unknown"
                    }
                    cursor.getColumnIndex(MediaStore.Images.Media.SIZE).takeIf { it >= 0 }?.let {
                        fileSize = cursor.getLong(it)
                    }
                    cursor.getColumnIndex(MediaStore.Images.Media.WIDTH).takeIf { it >= 0 }?.let {
                        width = cursor.getInt(it)
                    }
                    cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT).takeIf { it >= 0 }?.let {
                        height = cursor.getInt(it)
                    }
                    cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE).takeIf { it >= 0 }?.let {
                        mimeType = cursor.getString(it)
                    }
                    cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN).takeIf { it >= 0 }?.let {
                        dateTaken = cursor.getLong(it)
                    }
                    cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED).takeIf { it >= 0 }?.let {
                        dateModified = cursor.getLong(it) * 1000
                    }
                }
            }

            val existing = dao.findByUri(uri.toString())
            if (existing != null) {
                // Re-running indexing also upgrades older records with object metadata.
                val objects = runObjectDetection(uri)
                val objectText = serializeObjects(objects)
                if (objectText != existing.detectedObjects) {
                    dao.updateDetectedObjects(existing.id, objectText)
                }
                try {
                    semanticSearchService.ensureModel()
                    semanticSearchService.indexImageAndStore(existing)
                } catch (semanticError: Throwable) {
                    semanticError.printStackTrace()
                }
                return dao.getById(existing.id)
            }

            val ocr = ocrEngine.process(uri)
            val objects = runObjectDetection(uri)
            val entity = ImageEntity(
                uri = uri.toString(),
                fileName = fileName,
                filePath = uri.toString(),
                dateTaken = dateTaken,
                dateModified = dateModified,
                fileSize = fileSize,
                width = width,
                height = height,
                mimeType = mimeType,
                ocrText = ocr.text,
                ocrLanguage = ocr.language,
                detectedObjects = serializeObjects(objects),
                indexedAt = System.currentTimeMillis()
            )

            val id = dao.insert(entity)
            val saved = entity.copy(id = id)

            // Semantic indexing is best-effort. OCR + object indexing remains usable
            // when the large MobileCLIP model cannot be downloaded.
            try {
                semanticSearchService.ensureModel()
                semanticSearchService.indexImageAndStore(saved)
            } catch (semanticError: Throwable) {
                semanticError.printStackTrace()
            }

            saved
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    private fun runObjectDetection(uri: Uri): List<ObjectDetectionResult> {
        return try {
            objectDetector.detect(uri)
        } catch (t: Throwable) {
            t.printStackTrace()
            emptyList()
        }
    }

    private fun serializeObjects(objects: List<ObjectDetectionResult>): String {
        return objects
            .groupBy { it.classId }
            .values
            .joinToString("; ") { detections ->
                val best = detections.maxByOrNull { it.confidence } ?: return@joinToString ""
                val aliases = best.arabicLabel
                if (aliases.isBlank()) {
                    "${best.label}:${"%.3f".format(java.util.Locale.US, best.confidence)}"
                } else {
                    "${best.label}|$aliases:${"%.3f".format(java.util.Locale.US, best.confidence)}"
                }
            }
    }

    override fun close() {
        ocrEngine.close()
        objectDetector.close()
        semanticSearchService.close()
    }
}
