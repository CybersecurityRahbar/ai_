package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

class ArabicOcrEngine(private val context: Context) {
    private var tess: TessBaseAPI? = null

    private val tessBaseDir get() = File(context.filesDir, "tesseract")
    private val tessDataDir get() = File(tessBaseDir, "tessdata")
    private val arabicModel get() = File(tessDataDir, "ara.traineddata")

    private fun prepareTessData() {
        if (!tessDataDir.exists() && !tessDataDir.mkdirs() && !tessDataDir.exists()) {
            throw IllegalStateException("Unable to create Tesseract tessdata directory")
        }
        if (arabicModel.exists() && arabicModel.length() >= 100_000) return
        if (arabicModel.exists()) arabicModel.delete()
        context.assets.open("tessdata/ara.traineddata").use { input ->
            FileOutputStream(arabicModel).use { output -> input.copyTo(output) }
        }
        require(arabicModel.exists() && arabicModel.length() >= 100_000) {
            "Arabic OCR model is missing or invalid"
        }
    }

    private fun createEngine(): TessBaseAPI {
        prepareTessData()
        val engine = TessBaseAPI()
        try {
            require(engine.init(tessBaseDir.absolutePath, "ara")) { "Tesseract Arabic initialization failed" }
            engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            return engine
        } catch (t: Throwable) {
            try { engine.recycle() } catch (_: Throwable) { }
            throw t
        }
    }

    /**
     * Runs several page-layout modes and keeps the union of recognized lines.
     * This is substantially more reliable for Arabic screenshots containing
     * columns, labels, mixed line lengths and sparse text than a single PSM.
     */
    fun recognize(bitmap: Bitmap): String {
        if (bitmap.isRecycled) return ""
        return try {
            if (tess == null) tess = createEngine()
            val engine = tess ?: return ""
            val working = prepareBitmap(bitmap)
            val results = linkedSetOf<String>()
            for (mode in intArrayOf(
                TessBaseAPI.PageSegMode.PSM_AUTO,
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            )) {
                try {
                    engine.pageSegMode = mode
                    engine.setImage(working)
                    val text = engine.utF8Text?.trim().orEmpty()
                    if (text.isNotBlank()) results += text
                    engine.clear()
                } catch (_: Throwable) { }
            }
            if (working !== bitmap && !working.isRecycled) working.recycle()
            mergeText(results)
        } catch (t: Throwable) {
            t.printStackTrace()
            try { tess?.clear(); tess?.recycle() } catch (_: Throwable) { }
            tess = null
            ""
        }
    }

    private fun prepareBitmap(source: Bitmap): Bitmap {
        val maxDimension = 2400
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height).toFloat())
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (width != source.width || height != source.height) {
            Bitmap.createScaledBitmap(source, width, height, true)
        } else source

        // A light grayscale/contrast normalization helps Arabic glyphs on
        // screenshots while preserving the original image for other engines.
        val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }) }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        return out
    }

    private fun mergeText(parts: Set<String>): String {
        val lines = linkedSetOf<String>()
        for (part in parts) {
            part.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { lines += it }
        }
        return lines.joinToString("\n")
    }

    fun close() {
        try { tess?.clear() } catch (_: Throwable) { }
        try { tess?.recycle() } catch (_: Throwable) { }
        tess = null
    }
}
