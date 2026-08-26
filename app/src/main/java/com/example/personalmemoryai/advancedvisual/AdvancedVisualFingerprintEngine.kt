package com.example.personalmemoryai.advancedvisual

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Classical, non-neural visual analysis used only by the separate Advanced
 * Visual Intelligence section. All outputs are deterministic and versioned.
 */
class AdvancedVisualFingerprintEngine {
    companion object {
        const val ENGINE_VERSION = "ADVANCED-VISUAL-CLASSICAL-V1"
        private const val PYRAMID_SIZE = 16
        private const val LBP_SIZE = 64
        private const val LAYOUT_GRID = 8
        private const val GRADIENT_BINS = 24
        private const val MAX_SAMPLE_PIXELS = 256 * 256
    }

    data class Fingerprint(
        val grayPyramid: ByteArray,
        val colorMoments: ByteArray,
        val lbpHistogram: ByteArray,
        val gradientHistogram: ByteArray,
        val layoutSignature: ByteArray,
        val entropy: Float,
        val aspectRatio: Float
    )

    data class Score(
        val similarity: Float,
        val structure: Float,
        val color: Float,
        val texture: Float,
        val gradient: Float,
        val layout: Float,
        val entropy: Float,
        val aspect: Float,
        val evidence: List<String>
    )

    fun fingerprint(source: Bitmap): Fingerprint {
        val bitmap = normalize(source)
        try {
            val gray = grayPixels(bitmap)
            val rgb = bitmapToRgb(bitmap)
            val pyramid = buildGrayPyramid(gray, bitmap.width, bitmap.height)
            val color = buildColorMoments(rgb)
            val lbp = buildLbpHistogram(gray, bitmap.width, bitmap.height)
            val gradient = buildGradientHistogram(gray, bitmap.width, bitmap.height)
            val layout = buildLayoutSignature(gray, bitmap.width, bitmap.height)
            val entropy = entropy(gray)
            val aspect = bitmap.width.toFloat() / max(1, bitmap.height).toFloat()
            return Fingerprint(pyramid, color, lbp, gradient, layout, entropy, aspect)
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    fun compare(a: Fingerprint, b: Fingerprint): Score {
        val structure = byteSimilarity(a.grayPyramid, b.grayPyramid)
        val color = byteSimilarity(a.colorMoments, b.colorMoments)
        val texture = byteSimilarity(a.lbpHistogram, b.lbpHistogram)
        val gradient = byteSimilarity(a.gradientHistogram, b.gradientHistogram)
        val layout = byteSimilarity(a.layoutSignature, b.layoutSignature)
        val entropyScore = scalarSimilarity(a.entropy, b.entropy, 0.35f)
        val aspectScore = scalarSimilarity(a.aspectRatio, b.aspectRatio, 0.75f)

        val similarity = (
            structure * 0.28f +
            color * 0.20f +
            texture * 0.16f +
            gradient * 0.16f +
            layout * 0.12f +
            entropyScore * 0.05f +
            aspectScore * 0.03f
        ).coerceIn(0f, 1f)

        val evidence = buildList {
            if (structure >= 0.85f) add("strong_multi_scale_structure")
            else if (structure >= 0.65f) add("compatible_multi_scale_structure")
            if (color >= 0.85f) add("strong_color_distribution")
            else if (color >= 0.65f) add("compatible_color_distribution")
            if (texture >= 0.80f) add("texture_agreement")
            if (gradient >= 0.80f) add("gradient_orientation_agreement")
            if (layout >= 0.80f) add("spatial_layout_agreement")
            if (structure < 0.45f && gradient < 0.45f) add("weak_structure")
            if (similarity < 0.40f) add("insufficient_advanced_evidence")
        }
        return Score(similarity, structure, color, texture, gradient, layout, entropyScore, aspectScore, evidence)
    }

    private fun normalize(source: Bitmap): Bitmap {
        val largest = max(source.width, source.height)
        if (largest <= 256) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = 256f / largest.toFloat()
        return Bitmap.createScaledBitmap(
            source,
            max(1, (source.width * scale).toInt()),
            max(1, (source.height * scale).toInt()),
            true
        )
    }

    private fun bitmapToRgb(bitmap: Bitmap): IntArray {
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }

    private fun grayPixels(bitmap: Bitmap): FloatArray {
        val rgb = bitmapToRgb(bitmap)
        val gray = FloatArray(rgb.size)
        for (i in rgb.indices) {
            val p = rgb[i]
            val r = (p shr 16 and 255)
            val g = (p shr 8 and 255)
            val b = (p and 255)
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
        return gray
    }

    private fun buildGrayPyramid(gray: FloatArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(PYRAMID_SIZE * PYRAMID_SIZE)
        for (gy in 0 until PYRAMID_SIZE) {
            for (gx in 0 until PYRAMID_SIZE) {
                val x0 = gx * width / PYRAMID_SIZE
                val x1 = max(x0 + 1, (gx + 1) * width / PYRAMID_SIZE)
                val y0 = gy * height / PYRAMID_SIZE
                val y1 = max(y0 + 1, (gy + 1) * height / PYRAMID_SIZE)
                var sum = 0.0
                var count = 0
                for (y in y0 until min(y1, height)) {
                    val row = y * width
                    for (x in x0 until min(x1, width)) {
                        sum += gray[row + x]
                        count++
                    }
                }
                out[gy * PYRAMID_SIZE + gx] = quantize(if (count == 0) 0f else (sum / count).toFloat())
            }
        }
        return out
    }

    private fun buildColorMoments(rgb: IntArray): ByteArray {
        val channels = Array(4) { DoubleArray(3) }
        var satSum = 0.0
        var satSq = 0.0
        for (p in rgb) {
            val r = (p shr 16 and 255) / 255.0
            val g = (p shr 8 and 255) / 255.0
            val b = (p and 255) / 255.0
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val saturation = if (maxC == 0.0) 0.0 else (maxC - minC) / maxC
            channels[0][0] += r; channels[0][1] += g; channels[0][2] += b
            channels[1][0] += r * r; channels[1][1] += g * g; channels[1][2] += b * b
            channels[2][0] += r * r * r; channels[2][1] += g * g * g; channels[2][2] += b * b * b
            channels[3][0] += maxC; channels[3][1] += minC; channels[3][2] += saturation
            satSum += saturation; satSq += saturation * saturation
        }
        val n = max(1, rgb.size).toDouble()
        val values = ArrayList<Float>(15)
        for (row in 0..3) for (col in 0..2) values.add((channels[row][col] / n).toFloat())
        values.add((satSum / n).toFloat())
        values.add(sqrt(max(0.0, satSq / n - (satSum / n) * (satSum / n))).toFloat())
        values.add(1f - values[0])
        return ByteArray(values.size) { i -> quantize(values[i]) }
    }

    private fun buildLbpHistogram(gray: FloatArray, width: Int, height: Int): ByteArray {
        val small = resizeGray(gray, width, height, LBP_SIZE, LBP_SIZE)
        val hist = FloatArray(256)
        for (y in 1 until LBP_SIZE - 1) {
            for (x in 1 until LBP_SIZE - 1) {
                val center = small[y * LBP_SIZE + x]
                var code = 0
                code = code or ((if (small[(y - 1) * LBP_SIZE + x - 1] >= center) 1 else 0) shl 7)
                code = code or ((if (small[(y - 1) * LBP_SIZE + x] >= center) 1 else 0) shl 6)
                code = code or ((if (small[(y - 1) * LBP_SIZE + x + 1] >= center) 1 else 0) shl 5)
                code = code or ((if (small[y * LBP_SIZE + x + 1] >= center) 1 else 0) shl 4)
                code = code or ((if (small[(y + 1) * LBP_SIZE + x + 1] >= center) 1 else 0) shl 3)
                code = code or ((if (small[(y + 1) * LBP_SIZE + x] >= center) 1 else 0) shl 2)
                code = code or ((if (small[(y + 1) * LBP_SIZE + x - 1] >= center) 1 else 0) shl 1)
                code = code or (if (small[y * LBP_SIZE + x - 1] >= center) 1 else 0)
                hist[code] += 1f
            }
        }
        normalizeHistogram(hist)
        return ByteArray(hist.size) { i -> quantize(hist[i]) }
    }

    private fun buildGradientHistogram(gray: FloatArray, width: Int, height: Int): ByteArray {
        val hist = FloatArray(GRADIENT_BINS)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val p = y * width + x
                val gx = gray[p + 1] - gray[p - 1]
                val gy = gray[p + width] - gray[p - width]
                val mag = sqrt(gx * gx + gy * gy)
                if (mag < 0.015f) continue
                var angle = atan2(gy.toDouble(), gx.toDouble()) + Math.PI
                val bin = ((angle / (2.0 * Math.PI)) * GRADIENT_BINS).toInt().coerceIn(0, GRADIENT_BINS - 1)
                hist[bin] += mag
            }
        }
        normalizeHistogram(hist)
        return ByteArray(hist.size) { i -> quantize(hist[i]) }
    }

    private fun buildLayoutSignature(gray: FloatArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(LAYOUT_GRID * LAYOUT_GRID)
        for (gy in 0 until LAYOUT_GRID) {
            for (gx in 0 until LAYOUT_GRID) {
                val x0 = gx * width / LAYOUT_GRID
                val x1 = max(x0 + 1, (gx + 1) * width / LAYOUT_GRID)
                val y0 = gy * height / LAYOUT_GRID
                val y1 = max(y0 + 1, (gy + 1) * height / LAYOUT_GRID)
                var edge = 0f
                var count = 0
                for (y in y0 until min(y1, height - 1)) {
                    val row = y * width
                    for (x in x0 until min(x1, width - 1)) {
                        val dx = abs(gray[row + x + 1] - gray[row + x])
                        val dy = abs(gray[row + width + x] - gray[row + x])
                        edge += min(1f, dx + dy)
                        count++
                    }
                }
                out[gy * LAYOUT_GRID + gx] = quantize(if (count == 0) 0f else edge / count)
            }
        }
        return out
    }

    private fun entropy(gray: FloatArray): Float {
        val hist = IntArray(256)
        for (v in gray) hist[(v * 255f).toInt().coerceIn(0, 255)]++
        var e = 0.0
        val n = max(1, gray.size).toDouble()
        for (count in hist) {
            if (count == 0) continue
            val p = count / n
            e -= p * (ln(p) / ln(2.0))
        }
        return (e / 8.0).toFloat().coerceIn(0f, 1f)
    }

    private fun resizeGray(gray: FloatArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int): FloatArray {
        val out = FloatArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sy = ((y + 0.5f) * height / targetHeight - 0.5f).toInt().coerceIn(0, height - 1)
            for (x in 0 until targetWidth) {
                val sx = ((x + 0.5f) * width / targetWidth - 0.5f).toInt().coerceIn(0, width - 1)
                out[y * targetWidth + x] = gray[sy * width + sx]
            }
        }
        return out
    }

    private fun normalizeHistogram(hist: FloatArray) {
        val sum = hist.sum().coerceAtLeast(1e-9f)
        for (i in hist.indices) hist[i] /= sum
    }

    private fun quantize(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f).toInt().toByte()

    private fun byteSimilarity(a: ByteArray, b: ByteArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var meanAbs = 0.0
        for (i in a.indices) meanAbs += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) / 255.0
        return (1.0 - meanAbs / a.size).toFloat().coerceIn(0f, 1f)
    }

    private fun scalarSimilarity(a: Float, b: Float, scale: Float): Float {
        return (1f - abs(a - b) / max(scale, 1e-6f)).coerceIn(0f, 1f)
    }
}
