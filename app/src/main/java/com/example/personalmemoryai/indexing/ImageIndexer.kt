package com.example.personalmemoryai.indexing

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity

class ImageIndexer(
    private val context: Context
) {

    private val database =
        AppDatabase.getInstance(context)

    private val dao =
        database.imageDao()

    private val ocrEngine =
        OcrEngine(context)

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

            resolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->

                if (cursor.moveToFirst()) {

                    fun index(name: String): Int =
                        cursor.getColumnIndex(name)

                    val nameIndex =
                        index(MediaStore.Images.Media.DISPLAY_NAME)

                    if (nameIndex >= 0) {
                        fileName =
                            cursor.getString(nameIndex)
                                ?: "Unknown"
                    }

                    val sizeIndex =
                        index(MediaStore.Images.Media.SIZE)

                    if (sizeIndex >= 0) {
                        fileSize =
                            cursor.getLong(sizeIndex)
                    }

                    val widthIndex =
                        index(MediaStore.Images.Media.WIDTH)

                    if (widthIndex >= 0) {
                        width =
                            cursor.getInt(widthIndex)
                    }

                    val heightIndex =
                        index(MediaStore.Images.Media.HEIGHT)

                    if (heightIndex >= 0) {
                        height =
                            cursor.getInt(heightIndex)
                    }

                    val mimeIndex =
                        index(MediaStore.Images.Media.MIME_TYPE)

                    if (mimeIndex >= 0) {
                        mimeType =
                            cursor.getString(mimeIndex)
                    }

                    val takenIndex =
                        index(MediaStore.Images.Media.DATE_TAKEN)

                    if (takenIndex >= 0) {
                        dateTaken =
                            cursor.getLong(takenIndex)
                    }

                    val modifiedIndex =
                        index(MediaStore.Images.Media.DATE_MODIFIED)

                    if (modifiedIndex >= 0) {
                        dateModified =
                            cursor.getLong(modifiedIndex) * 1000
                    }
                }
            }

            val existing =
                dao.findByUri(uri.toString())

            if (existing != null) {
                return existing
            }

            val ocr =
                ocrEngine.process(uri)

            val entity =
                ImageEntity(
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
                    indexedAt = System.currentTimeMillis()
                )

            val id =
                dao.insert(entity)

            entity.copy(id = id)

        } catch (t: Throwable) {

            t.printStackTrace()

            null
        }
    }

    fun close() {
        ocrEngine.close()
    }
}
