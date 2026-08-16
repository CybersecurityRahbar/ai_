package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

class ArabicOcrEngine(
    private val context: Context
) {

    private var tess: TessBaseAPI? = null

    init {
        prepareTessData()
    }

    private fun prepareTessData() {

        val tessDir =
            File(
                context.filesDir,
                "tesseract"
            )

        val tessDataDir =
            File(
                tessDir,
                "tessdata"
            )

        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs()
        }

        val target =
            File(
                tessDataDir,
                "ara.traineddata"
            )

        if (!target.exists() ||
            target.length() < 100_000
        ) {

            if (target.exists()) {
                target.delete()
            }

            context.assets
                .open(
                    "tessdata/ara.traineddata"
                )
                .use { input ->

                    FileOutputStream(
                        target
                    ).use { output ->

                        input.copyTo(output)
                    }
                }
        }

        if (!target.exists() ||
            target.length() < 100_000
        ) {

            throw IllegalStateException(
                "Arabic OCR model ara.traineddata is missing or invalid"
            )
        }
    }

    private fun createEngine(): TessBaseAPI {

        val basePath =
            File(
                context.filesDir,
                "tesseract"
            ).absolutePath

        val engine =
            TessBaseAPI()

        val initialized =
            engine.init(
                basePath,
                "ara"
            )

        if (!initialized) {

            engine.recycle()

            throw IllegalStateException(
                "Failed to initialize Arabic Tesseract OCR"
            )
        }

        engine.pageSegMode =
            TessBaseAPI.PageSegMode.PSM_AUTO

        return engine
    }

    fun recognize(
        bitmap: Bitmap
    ): String {

        if (bitmap.isRecycled) {
            return ""
        }

        if (tess == null) {
            tess = createEngine()
        }

        val engine =
            tess ?: return ""

        engine.setImage(bitmap)

        return engine.utF8Text
            ?.trim()
            ?: ""
    }

    fun close() {

        tess?.clear()
        tess?.recycle()
        tess = null
    }
}
