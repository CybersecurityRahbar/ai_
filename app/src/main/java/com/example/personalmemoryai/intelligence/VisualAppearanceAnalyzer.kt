package com.example.personalmemoryai.intelligence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deterministic appearance evidence used alongside learned embeddings.
 * It captures coarse clothing/body colors and spatial scene layout so the
 * fusion layer can verify a visual match instead of relying on one model.
 */
class VisualAppearanceAnalyzer(private val context: Context) {
    data class Descriptor(
        val colorHistogram: FloatArray,
        val spatialHistogram: FloatArray,
        val edgeGrid: FloatArray,
        val aspectRatio: Float,
        val available: Boolean
    )

    fun analyze(uri: Uri): Descriptor? {
        val bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) { null } ?: return null
        return try { analyze(bitmap) } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    fun analyze(bitmap: Bitmap): Descriptor {
        val scaled = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        val global = FloatArray(24)
        val spatial = FloatArray(24 * 9)
        val edges = FloatArray(9)
        for (y in 0 until 96) {
            for (x in 0 until 96) {
                val c = scaled.getPixel(x, y)
                val r = ((c shr 16) and 255) / 255f
                val g = ((c shr 8) and 255) / 255f
                val b = (c and 255) / 255f
                val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
                val v = max; val s = if (max == 0f) 0f else d / max
                var h = when {
                    d == 0f -> 0f
                    max == r -> ((g - b) / d) % 6f
                    max == g -> (b - r) / d + 2f
                    else -> (r - g) / d + 4f
                } / 6f
                if (h < 0f) h += 1f
                val hb = (h * 8).toInt().coerceIn(0, 7)
                val sb = (s * 2).toInt().coerceIn(0, 1)
                val vb = (v * 2).toInt().coerceIn(0, 1)
                val bin = hb * 3 + sb + vb
                global[bin.coerceIn(0, 23)] += 1f
                val cell = (y / 32) * 3 + (x / 32)
                spatial[cell * 24 + bin.coerceIn(0, 23)] += 1f
                if (x > 0 && y > 0) {
                    val p = scaled.getPixel(x - 1, y - 1)
                    val pr = ((p shr 16) and 255) / 255f; val pg = ((p shr 8) and 255) / 255f; val pb = (p and 255) / 255f
                    edges[cell] += abs((r + g + b) - (pr + pg + pb)) / 3f
                }
            }
        }
        normalize(global)
        for (i in 0 until 9) normalize(spatial, i * 24, 24)
        for (i in edges.indices) edges[i] = (edges[i] / 1024f).coerceIn(0f, 1f)
        val result = Descriptor(global, spatial, edges, bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1), true)
        if (!scaled.isRecycled) scaled.recycle()
        return result
    }

    fun colorSimilarity(a: Descriptor, b: Descriptor): Float = histogramSimilarity(a.colorHistogram, b.colorHistogram)

    fun sceneSimilarity(a: Descriptor, b: Descriptor): Float {
        val spatial = histogramSimilarity(a.spatialHistogram, b.spatialHistogram)
        val edges = 1f - (a.edgeGrid.zip(b.edgeGrid).map { abs(it.first - it.second) }.average().toFloat()).coerceIn(0f, 1f)
        val aspect = (1f - abs(a.aspectRatio - b.aspectRatio) / maxOf(a.aspectRatio, b.aspectRatio, 1f)).coerceIn(0f, 1f)
        return (spatial * 0.70f + edges * 0.20f + aspect * 0.10f).coerceIn(0f, 1f)
    }

    private fun histogramSimilarity(a: FloatArray, b: FloatArray): Float {
        var intersection = 0f
        var total = 0f
        for (i in a.indices) { intersection += minOf(a[i], b[i]); total += maxOf(a[i], b[i]) }
        return if (total <= 0f) 0f else (intersection / total).coerceIn(0f, 1f)
    }

    private fun normalize(values: FloatArray, offset: Int = 0, length: Int = values.size) {
        var total = 0f
        for (i in offset until minOf(values.size, offset + length)) total += values[i]
        if (total <= 0f) return
        for (i in offset until minOf(values.size, offset + length)) values[i] /= total
    }
}
