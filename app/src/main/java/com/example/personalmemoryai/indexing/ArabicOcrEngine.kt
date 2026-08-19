package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

internal data class ArabicOcrResult(
    val text: String,
    val qualityScore: Float,
    val passCount: Int,
    val successfulPasses: Int
)

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

    fun recognize(bitmap: Bitmap): String = recognizeDetailed(bitmap).text

    fun recognizeDetailed(uri: Uri): ArabicOcrResult {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ArabicOcrResult("", 0f, 0, 0)
        var sample = 1
        val maxDimension = 4096
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
            ?: return ArabicOcrResult("", 0f, 0, 0)
        return try { recognizeDetailed(bitmap) } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    fun recognizeDetailed(bitmap: Bitmap): ArabicOcrResult {
        if (bitmap.isRecycled) return ArabicOcrResult("", 0f, 0, 0)
        return try {
            if (tess == null) tess = createEngine()
            val engine = tess ?: return ArabicOcrResult("", 0f, 0, 0)
            val variants = prepareVariants(bitmap)
            val results = linkedSetOf<String>()
            var successful = 0
            var bestConfidence = 0
            var passes = 0
            for (working in variants) {
                for (mode in intArrayOf(
                    TessBaseAPI.PageSegMode.PSM_AUTO,
                    TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                    TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                    TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                )) {
                    passes++
                    try {
                        engine.pageSegMode = mode
                        engine.setImage(working)
                        val text = engine.utF8Text?.trim().orEmpty()
                        val confidence = engine.meanConfidence().coerceIn(0, 100)
                        if (text.isNotBlank()) {
                            results += text
                            successful++
                            if (confidence > bestConfidence) bestConfidence = confidence
                        }
                        engine.clear()
                    } catch (_: Throwable) {}
                }
                if (working !== bitmap && !working.isRecycled) working.recycle()
            }
            val merged = mergeText(results)
            val quality = if (merged.isBlank()) 0f else ((bestConfidence / 100f) * 0.75f + repetitionScore(results) * 0.25f).coerceIn(0f, 1f)
            ArabicOcrResult(merged, quality, passes, successful)
        } catch (t: Throwable) {
            t.printStackTrace()
            try { tess?.clear(); tess?.recycle() } catch (_: Throwable) {}
            tess = null
            ArabicOcrResult("", 0f, 0, 0)
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

    private fun repetitionScore(parts: Set<String>): Float {
        if (parts.size <= 1) return if (parts.firstOrNull().isNullOrBlank()) 0f else 0.5f
        val normalized = parts.map { it.replace(Regex("\\s+"), " ").trim() }
        val longest = normalized.maxOfOrNull { it.length } ?: return 0f
        if (longest == 0) return 0f
        val near = normalized.count { it.length >= longest * 0.35 }
        return (near.toFloat() / parts.size).coerceIn(0f, 1f)
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
