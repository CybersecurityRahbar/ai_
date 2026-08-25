package com.example.personalmemoryai.reverseimage

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * digiKam-compatible algorithmic core, implemented natively for Android.
 *
 * Follows digiKam's source-level Haar implementation:
 * 128x128 -> RGB/YIQ -> separable Haar -> strongest 40 signed coefficients
 * per channel -> Y/I/Q averages -> weighted signed-coefficient distance.
 *
 * The serialized format is app-owned; it is not claimed byte-for-byte
 * compatible with digiKam's ImageHaarMatrix BLOB.
 */
class HaarFingerprintEngine {
    companion object {
        const val ENGINE_VERSION = "DIGIKAM-HAAR-128-40-YIQ-V2"
        const val SIZE = 128
        const val PIXELS = SIZE * SIZE
        const val CHANNELS = 3
        const val COEFFICIENTS = 40
        private const val MAGIC = 0x444B4832 // DKH2

        // digiKam Haar::s_haar_weights, ScannedSketch profile.
        private val HAAR_WEIGHTS = arrayOf(
            floatArrayOf(5.00f, 19.21f, 34.37f),
            floatArrayOf(0.83f, 1.26f, 0.36f),
            floatArrayOf(1.01f, 0.44f, 0.45f),
            floatArrayOf(0.52f, 0.53f, 0.14f),
            floatArrayOf(0.47f, 0.28f, 0.18f),
            floatArrayOf(0.30f, 0.14f, 0.27f)
        )
    }

    data class Fingerprint(val width: Int, val height: Int, val channels: Int = CHANNELS, val signature: ByteArray)
    data class Score(val similarity: Float, val matchedCoefficients: Int)
    private data class Decoded(val avg: DoubleArray, val signedIndices: Array<IntArray>)

    fun fingerprint(bitmap: Bitmap): Fingerprint {
        require(bitmap.width > 0 && bitmap.height > 0) { "Invalid bitmap dimensions" }
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        return try {
            val r = DoubleArray(PIXELS)
            val g = DoubleArray(PIXELS)
            val b = DoubleArray(PIXELS)
            val pixels = IntArray(PIXELS)
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            for (p in pixels.indices) {
                r[p] = ((pixels[p] ushr 16) and 0xFF).toDouble()
                g[p] = ((pixels[p] ushr 8) and 0xFF).toDouble()
                b[p] = (pixels[p] and 0xFF).toDouble()
            }

            val y = DoubleArray(PIXELS)
            val i = DoubleArray(PIXELS)
            val q = DoubleArray(PIXELS)
            for (p in 0 until PIXELS) {
                y[p] = 0.299 * r[p] + 0.587 * g[p] + 0.114 * b[p]
                i[p] = 0.596 * r[p] - 0.275 * g[p] - 0.321 * b[p]
                q[p] = 0.212 * r[p] - 0.523 * g[p] + 0.311 * b[p]
            }

            haar2D(y)
            haar2D(i)
            haar2D(q)
            y[0] /= (256.0 * 128.0)
            i[0] /= (256.0 * 128.0)
            q[0] /= (256.0 * 128.0)

            Fingerprint(
                width = bitmap.width,
                height = bitmap.height,
                channels = CHANNELS,
                signature = serialize(
                    doubleArrayOf(y[0], i[0], q[0]),
                    arrayOf(strongestSignedIndices(y), strongestSignedIndices(i), strongestSignedIndices(q))
                )
            )
        } finally {
            scaled.recycle()
        }
    }

    fun compare(query: Fingerprint, target: Fingerprint): Score {
        val q = decode(query.signature)
        val t = decode(target.signature)
        var score = 0.0

        // digiKam step 1: weighted distance between Y/I/Q averages.
        for (channel in 0 until CHANNELS) {
            score += HAAR_WEIGHTS[0][channel] * abs(q.avg[channel] - t.avg[channel])
        }

        // digiKam step 2: common signed significant coefficients lower the score.
        var matched = 0
        for (channel in 0 until CHANNELS) {
            val queryMap = HashSet<Int>(COEFFICIENTS * 2)
            q.signedIndices[channel].forEach(queryMap::add)
            for (x in t.signedIndices[channel]) {
                if (queryMap.contains(x)) {
                    score -= HAAR_WEIGHTS[weightBin(abs(x))][channel]
                    matched++
                }
            }
        }

        // Normalize with digiKam's best/worst possible range.
        var worst = 0.0
        for (channel in 0 until CHANNELS) {
            worst += HAAR_WEIGHTS[0][channel] * abs(q.avg[channel])
        }
        var best = 0.0
        for (channel in 0 until CHANNELS) {
            for (x in q.signedIndices[channel]) {
                best -= HAAR_WEIGHTS[weightBin(abs(x))][channel]
            }
        }

        val range = worst - best
        val similarity = if (range <= 1e-12) {
            if (score <= best + 1e-12) 1f else 0f
        } else {
            (1.0 - ((score - best) / range)).toFloat().coerceIn(0f, 1f)
        }
        return Score(similarity, matched)
    }

    private fun strongestSignedIndices(data: DoubleArray): IntArray {
        val indices = (1 until PIXELS).sortedByDescending { abs(data[it]) }.take(COEFFICIENTS)
        return IntArray(COEFFICIENTS) { n ->
            val index = indices[n]
            if (data[index] <= 0.0) -index else index
        }
    }

    /** Exact separable 2-D Haar scheme used by digiKam's Calculator. */
    private fun haar2D(data: DoubleArray) {
        val temp = DoubleArray(SIZE)

        // Rows.
        for (rowStart in 0 until PIXELS step SIZE) {
            var h = SIZE
            var c = 1.0
            while (h > 1) {
                val half = h shr 1
                c *= 0.7071
                for (k in 0 until half) {
                    val j2 = rowStart + 2 * k
                    val j21 = j2 + 1
                    temp[k] = (data[j2] - data[j21]) * c
                    data[rowStart + k] = data[j2] + data[j21]
                }
                for (k in 0 until half) data[rowStart + half + k] = temp[k]
                h = half
            }
            data[rowStart] *= c
        }

        // Columns.
        for (column in 0 until SIZE) {
            var h = SIZE
            var c = 1.0
            while (h > 1) {
                val half = h shr 1
                c *= 0.7071
                for (k in 0 until half) {
                    val j2 = 2 * k * SIZE + column
                    val j21 = j2 + SIZE
                    temp[k] = (data[j2] - data[j21]) * c
                    data[k * SIZE + column] = data[j2] + data[j21]
                }
                for (k in 0 until half) data[(half + k) * SIZE + column] = temp[k]
                h = half
            }
            data[column] *= c
        }
    }

    /** digiKam WeightBin: value at (row,col) is min(max(row,col), 5). */
    private fun weightBin(index: Int): Int {
        val row = index / SIZE
        val col = index % SIZE
        return max(row, col).coerceAtMost(5)
    }

    private fun serialize(avg: DoubleArray, channels: Array<IntArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).putInt(SIZE).putInt(COEFFICIENTS).putInt(CHANNELS)
        out.write(header.array())

        val avgBuffer = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        avg.forEach(avgBuffer::putDouble)
        out.write(avgBuffer.array())

        for (channel in channels) {
            val buffer = ByteBuffer.allocate(COEFFICIENTS * 4).order(ByteOrder.BIG_ENDIAN)
            channel.forEach(buffer::putInt)
            out.write(buffer.array())
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= 16 + 24 + CHANNELS * COEFFICIENTS * 4) { "Invalid Haar fingerprint" }
        val header = ByteBuffer.wrap(bytes, 0, 16).order(ByteOrder.BIG_ENDIAN)
        require(header.int == MAGIC) { "Unsupported Haar fingerprint format" }
        require(header.int == SIZE && header.int == COEFFICIENTS && header.int == CHANNELS) {
            "Incompatible Haar fingerprint dimensions"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(16)
        val avg = DoubleArray(CHANNELS) { buffer.double }
        val channels = Array(CHANNELS) { IntArray(COEFFICIENTS) }
        for (channel in 0 until CHANNELS) {
            for (j in 0 until COEFFICIENTS) channels[channel][j] = buffer.int
        }
        return Decoded(avg, channels)
    }

    fun cropCentered(bitmap: Bitmap, fraction: Float): Bitmap {
        val f = fraction.coerceIn(0.50f, 1f)
        val w = (bitmap.width * f).toInt().coerceAtLeast(2)
        val h = (bitmap.height * f).toInt().coerceAtLeast(2)
        val left = ((bitmap.width - w) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - h) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }
}
