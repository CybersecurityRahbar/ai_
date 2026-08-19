package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

class ArabicOcrEngine(private val context: Context) {
    private var tess: TessBaseAPI? = null

    private val tessBaseDir get() = File(context.filesDir, "tesseract")
    private val tessDataDir get() = File(tessBaseDir, "tessdata")
    private val arabicModel get() = File(tessDataDir, "ara.traineddata")

    private fun prepareTessData() {
        if (!tessDataDir.exists() && !tessDataDir.mkdirs() && !tessDataDir.exists()) throw IllegalStateException("Unable to create Tesseract tessdata directory")
        if (arabicModel.exists() && arabicModel.length() >= 100_000) return
        if (arabicModel.exists()) arabicModel.delete()
        context.assets.open("tessdata/ara.traineddata").use { input -> FileOutputStream(arabicModel).use { output -> input.copyTo(output) } }
        require(arabicModel.exists() && arabicModel.length() >= 100_000) { "Arabic OCR model is missing or invalid" }
    }

    private fun createEngine(): TessBaseAPI {
        prepareTessData()
        val engine = TessBaseAPI()
        try {
            require(engine.init(tessBaseDir.absolutePath, "ara")) { "Tesseract Arabic initialization failed" }
            engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            return engine
        } catch (t: Throwable) {
            try { engine.recycle() } catch (_: Throwable) {}
            throw t
        }
    }

    /** Multi-pass Arabic OCR: color + grayscale + contrast, with several layout modes. */
    fun recognize(bitmap: Bitmap): String {
        if (bitmap.isRecycled) return ""
        return try {
            if (tess == null) tess = createEngine()
            val engine = tess ?: return ""
            val variants = prepareVariants(bitmap)
            val results = linkedSetOf<String>()
            for (working in variants) {
                for (mode in intArrayOf(
                    TessBaseAPI.PageSegMode.PSM_AUTO,
                    TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                    TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                    TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                )) {
                    try {
                        engine.pageSegMode = mode
                        engine.setImage(working)
                        val text = engine.utF8Text?.trim().orEmpty()
                        if (text.isNotBlank()) results += text
                        engine.clear()
                    } catch (_: Throwable) {}
                }
                if (working !== bitmap && !working.isRecycled) working.recycle()
            }
            mergeText(results)
        } catch (t: Throwable) {
            t.printStackTrace()
            try { tess?.clear(); tess?.recycle() } catch (_: Throwable) {}
            tess = null
            ""
        }
    }

    private fun prepareVariants(source: Bitmap): List<Bitmap> {
        val maxDimension = 4096
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height).toFloat())
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val base = if (width != source.width || height != source.height) Bitmap.createScaledBitmap(source, width, height, true) else source

        val gray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(gray).drawBitmap(base, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        })

        val contrast = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val contrastMatrix = ColorMatrix(floatArrayOf(
            1.35f, 0f, 0f, 0f, -35f,
            0f, 1.35f, 0f, 0f, -35f,
            0f, 0f, 1.35f, 0f, -35f,
            0f, 0f, 0f, 1f, 0f
        ))
        Canvas(contrast).drawBitmap(base, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(contrastMatrix)
        })

        if (base !== source && !base.isRecycled) base.recycle()
        return listOf(source, gray, contrast)
    }

    private fun mergeText(parts: Set<String>): String {
        val lines = linkedSetOf<String>()
        for (part in parts) {
            part.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { lines += it }
        }
        return lines.joinToString("\n")
    }

    fun close() {
        try { tess?.clear() } catch (_: Throwable) {}
        try { tess?.recycle() } catch (_: Throwable) {}
        tess = null
    }
}
