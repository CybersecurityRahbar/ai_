package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
            require(engine.init(tessBaseDir.absolutePath, "ara")) {
                "Tesseract Arabic initialization failed"
            }
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
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ArabicOcrResult("", 0f, 0, 0)
        }
        var sample = 1
        val maxDimension = 4096
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, options)
        } ?: return ArabicOcrResult("", 0f, 0, 0)
        return try { recognizeDetailed(bitmap) } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private data class Candidate(val text: String, val confidence: Float, val mode: Int)

    fun recognizeDetailed(bitmap: Bitmap): ArabicOcrResult {
        if (bitmap.isRecycled) return ArabicOcrResult("", 0f, 0, 0)
        return try {
            if (tess == null) tess = createEngine()
            val engine = tess ?: return ArabicOcrResult("", 0f, 0, 0)
            val variants = prepareVariants(bitmap)
            val candidates = mutableListOf<Candidate>()
            var successful = 0
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
                        val text = sanitizeCandidate(engine.utF8Text.orEmpty())
                        val confidence = engine.meanConfidence().coerceIn(0, 100).toFloat()
                        if (text.isNotBlank()) {
                            candidates += Candidate(text, confidence, mode)
                            successful++
                        }
                        engine.clear()
                    } catch (_: Throwable) {}
                }
                if (working !== bitmap && !working.isRecycled) working.recycle()
            }

            val merged = selectAndMergeCandidates(candidates)
            val bestConfidence = candidates.maxOfOrNull { it.confidence } ?: 0f
            val consistency = candidateConsistency(candidates, merged)
            val textDensity = textDensity(merged)
            val quality = if (merged.isBlank()) 0f else (
                (bestConfidence / 100f) * 0.55f +
                    consistency * 0.25f +
                    textDensity * 0.20f
                ).coerceIn(0f, 1f)

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
        val baseScale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height).toFloat())
        val width = (source.width * baseScale).toInt().coerceAtLeast(1)
        val height = (source.height * baseScale).toInt().coerceAtLeast(1)
        val base = if (width != source.width || height != source.height) {
            Bitmap.createScaledBitmap(source, width, height, true)
        } else source
        val variants = mutableListOf<Bitmap>(source)

        if (maxOf(width, height) < 2800) {
            val scale = minOf(1.6f, maxDimension.toFloat() / maxOf(width, height).toFloat())
            val up = Bitmap.createScaledBitmap(base, (width * scale).toInt(), (height * scale).toInt(), true)
            variants += up
        }

        val gray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(gray).drawBitmap(base, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        })
        variants += gray

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
        variants += contrast

        val threshold = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        gray.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val luminance = Color.red(pixels[i]) * 0.299f + Color.green(pixels[i]) * 0.587f + Color.blue(pixels[i]) * 0.114f
            pixels[i] = if (luminance >= 160f) Color.WHITE else Color.BLACK
        }
        threshold.setPixels(pixels, 0, width, 0, 0, width, height)
        variants += threshold

        if (base !== source && !base.isRecycled) base.recycle()
        return variants
    }

    private fun sanitizeCandidate(value: String): String {
        return value.lineSequence()
            .map { it.replace(Regex("[\\u0000-\\u001F]"), " ").trim() }
            .filter { line -> line.any { it.isLetterOrDigit() || it in "،؛,:.!؟?-/()[]%+=" } }
            .joinToString("\n")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    /** Prefer high-confidence complete candidates, then add only non-duplicate lines from other strong passes. */
    private fun selectAndMergeCandidates(candidates: List<Candidate>): String {
        if (candidates.isEmpty()) return ""
        val ranked = candidates
            .distinctBy { normalizeForComparison(it.text) }
            .sortedByDescending { candidateValue(it) }
        val strong = ranked.filter { it.confidence >= 35f || it.text.length >= 12 }
        val pool = if (strong.isNotEmpty()) strong else ranked
        val lines = linkedMapOf<String, String>()
        for (candidate in pool.take(6)) {
            for (line in candidate.text.lineSequence()) {
                val clean = line.trim()
                if (clean.length < 2) continue
                val key = normalizeForComparison(clean)
                if (key.isBlank()) continue
                val existing = lines.keys.firstOrNull { similarText(it, key) }
                if (existing == null) lines[key] = clean
            }
        }
        return lines.values.joinToString("\n")
    }

    private fun candidateValue(candidate: Candidate): Float {
        val lengthBonus = (candidate.text.length / 500f).coerceIn(0f, 1f) * 20f
        return candidate.confidence + lengthBonus + if (candidate.mode == TessBaseAPI.PageSegMode.PSM_AUTO) 3f else 0f
    }

    private fun candidateConsistency(candidates: List<Candidate>, merged: String): Float {
        if (candidates.isEmpty() || merged.isBlank()) return 0f
        val target = normalizeForComparison(merged)
        val supporting = candidates.count { candidate ->
            val value = normalizeForComparison(candidate.text)
            value.length >= 4 && (value.contains(target.take(minOf(20, target.length))) || target.contains(value.take(minOf(20, value.length))))
        }
        return (supporting.toFloat() / candidates.size).coerceIn(0f, 1f)
    }

    private fun textDensity(text: String): Float {
        if (text.isBlank()) return 0f
        val useful = text.count { it.isLetterOrDigit() }
        return (useful.toFloat() / text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private fun normalizeForComparison(value: String): String = value
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[\\u0622\\u0623\\u0625]"), "ا")
        .replace('ى', 'ي')
        .replace('ة', 'ه')
        .replace('ـ', ' ')
        .replace(Regex("\\s+"), "")
        .lowercase()

    private fun similarText(a: String, b: String): Boolean {
        if (a == b) return true
        val short = minOf(a.length, b.length)
        if (short < 6) return false
        val prefix = minOf(12, short)
        return a.take(prefix) == b.take(prefix) || a.takeLast(prefix) == b.takeLast(prefix)
    }

    fun close() {
        try { tess?.clear() } catch (_: Throwable) {}
        try { tess?.recycle() } catch (_: Throwable) {}
        tess = null
    }
}
