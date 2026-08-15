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
            File(context.filesDir, "tesseract")

        val tessDataDir =
            File(tessDir, "tessdata")

        if (!tessDataDir.exists()) {
            tessDataDir.mkdirs()
        }

        val target =
            File(tessDataDir, "ara.traineddata")

        if (!target.exists()) {

            context.assets.open(
                "tessdata/ara.traineddata"
            ).use { input ->

                FileOutputStream(target).use { output ->

                    input.copyTo(output)
                }
            }
        }
    }

    private fun createEngine(): TessBaseAPI {

        val basePath =
            File(
                context.filesDir,
                "tesseract"
            ).absolutePath

        return TessBaseAPI().apply {

            init(
                basePath,
                "ara"
            )

            pageSegMode =
                TessBaseAPI.PageSegMode.PSM_AUTO
        }
    }

    fun recognize(bitmap: Bitmap): String {

        if (tess == null) {
            tess = createEngine()
        }

        val engine = tess ?: return ""

        engine.setImage(bitmap)

        return engine.utF8Text
            ?.trim()
            ?: ""
    }

    fun close() {

        tess?.clear()
        tess?.end()
        tess = null
    }
}
