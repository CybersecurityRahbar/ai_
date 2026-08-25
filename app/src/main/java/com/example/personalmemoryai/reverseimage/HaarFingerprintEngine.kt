package com.example.personalmemoryai.reverseimage

import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ln

/**
 * Standalone digiKam-style local image fingerprint engine.
 *
 * It follows the documented Fast Multi-Resolution Image Querying recipe:
 * fixed-size image -> YIQ -> Haar transform -> strongest coefficients -> signed quantization.
 * The serialized bytes are our Android format, not a claimed byte-for-byte digiKam blob.
 */
class HaarFingerprintEngine {
    companion object {
        const val ENGINE_VERSION = "FMRIQ-HAAR-YIQ-128-V1"
        const val SIZE = 128
        const val TOP_COEFFICIENTS = 60
        const val CHANNELS = 3
        private const val SIGNATURE_MAGIC = 0x48414131
        private const val HEADER_BYTES = 16
        private const val COEFFICIENT_BYTES = TOP_COEFFICIENTS * CHANNELS * 3
    }

    data class Fingerprint(val width: Int, val height: Int, val channels: Int = CHANNELS, val signature: ByteArray)
    data class Score(val similarity: Float, val matchedCoefficients: Int)

    fun fingerprint(bitmap: Bitmap): Fingerprint {
        require(bitmap.width > 0 && bitmap.height > 0) { "Invalid bitmap dimensions" }
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        return try {
            val channels = Array(CHANNELS) { FloatArray(SIZE * SIZE) }
            val pixels = IntArray(SIZE * SIZE)
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            for (i in pixels.indices) {
                val r = ((pixels[i] shr 16) and 0xFF) / 255f
                val g = ((pixels[i] shr 8) and 0xFF) / 255f
                val b = (pixels[i] and 0xFF) / 255f
                channels[0][i] = 0.299f * r + 0.587f * g + 0.114f * b
                channels[1][i] = 0.596f * r - 0.274f * g - 0.322f * b
                channels[2][i] = 0.211f * r - 0.523f * g + 0.312f * b
            }
            Fingerprint(bitmap.width, bitmap.height, CHANNELS, serialize(channels.map(::extractChannelSignature).toTypedArray()))
        } finally { scaled.recycle() }
    }

    fun fingerprint(uri: Uri, open: (Uri) -> Bitmap?): Fingerprint {
        val bitmap = requireNotNull(open(uri)) { "Unable to decode image: $uri" }
        return try { fingerprint(bitmap) } finally { bitmap.recycle() }
    }

    fun compare(query: Fingerprint, target: Fingerprint): Score {
        val q = deserialize(query.signature)
        val t = deserialize(target.signature)
        require(q.size == CHANNELS && t.size == CHANNELS) { "Unsupported fingerprint channel count" }
        var totalWeight = 0.0
        var matched = 0
        for (channel in 0 until CHANNELS) {
            val qMap = q[channel].associate { it.first to it.second }
            val tMap = t[channel].associate { it.first to it.second }
            for ((index, qSign) in q[channel]) {
                val rank = q[channel].indexOfFirst { it.first == index }.coerceAtLeast(0)
                totalWeight += (1.0 + ln(TOP_COEFFICIENTS.toDouble() / (1.0 + rank))).coerceIn(0.1, 2.0)
                if (tMap[index] == qSign) matched++
            }
            for ((index, _) in t[channel]) if (index !in qMap) totalWeight += 0.12
        }
        val similarity = if (totalWeight == 0.0) 0f else (matched / totalWeight).toFloat().coerceIn(0f, 1f)
        return Score(similarity, matched)
    }

    private fun extractChannelSignature(channel: FloatArray): List<Pair<Int, Byte>> {
        val transformed = channel.copyOf()
        haar2D(transformed)
        return transformed.indices
            .filter { it != 0 }
            .sortedByDescending { abs(transformed[it]) }
            .take(TOP_COEFFICIENTS)
            .map { index -> index to if (transformed[index] > 0f) 1.toByte() else if (transformed[index] < 0f) (-1).toByte() else 0.toByte() }
    }

    private fun haar2D(data: FloatArray) {
        val temp = FloatArray(SIZE * SIZE)
        var width = SIZE
        var height = SIZE
        while (width > 1 || height > 1) {
            if (width > 1) {
                for (y in 0 until height) {
                    val row = y * SIZE
                    var x = 0
                    var out = 0
                    while (x < width) {
                        val a = data[row + x]; val b = data[row + x + 1]
                        temp[row + out] = (a + b) * 0.5f
                        temp[row + width / 2 + out] = (a - b) * 0.5f
                        x += 2; out++
                    }
                }
                for (y in 0 until height) for (x in 0 until width) data[y * SIZE + x] = temp[y * SIZE + x]
                width /= 2
            }
            if (height > 1) {
                for (x in 0 until width) {
                    var y = 0
                    var out = 0
                    while (y < height) {
                        val a = data[y * SIZE + x]; val b = data[(y + 1) * SIZE + x]
                        temp[out * SIZE + x] = (a + b) * 0.5f
                        temp[(height / 2 + out) * SIZE + x] = (a - b) * 0.5f
                        y += 2; out++
                    }
                }
                for (y in 0 until height) for (x in 0 until width) data[y * SIZE + x] = temp[y * SIZE + x]
                height /= 2
            }
        }
    }

    private fun serialize(channels: Array<List<Pair<Int, Byte>>>): ByteArray {
        val out = ByteArrayOutputStream(HEADER_BYTES + COEFFICIENT_BYTES)
        fun putInt(v: Int) { out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 8) and 0xFF); out.write(v and 0xFF) }
        putInt(SIGNATURE_MAGIC); putInt(SIZE); putInt(TOP_COEFFICIENTS); putInt(CHANNELS)
        channels.forEach { it.forEach { (index, sign) -> out.write((index ushr 8) and 0xFF); out.write(index and 0xFF); out.write(sign.toInt()) } }
        return out.toByteArray()
    }

    private fun deserialize(bytes: ByteArray): Array<List<Pair<Int, Byte>>> {
        require(bytes.size >= HEADER_BYTES) { "Invalid fingerprint" }
        fun intAt(o: Int): Int = ((bytes[o].toInt() and 0xFF) shl 24) or ((bytes[o + 1].toInt() and 0xFF) shl 16) or ((bytes[o + 2].toInt() and 0xFF) shl 8) or (bytes[o + 3].toInt() and 0xFF)
        require(intAt(0) == SIGNATURE_MAGIC) { "Unknown fingerprint format" }
        require(intAt(4) == SIZE && intAt(8) == TOP_COEFFICIENTS && intAt(12) == CHANNELS) { "Incompatible fingerprint version" }
        var offset = HEADER_BYTES
        return Array(CHANNELS) { List(TOP_COEFFICIENTS) { val index = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF); val sign = bytes[offset + 2]; offset += 3; index to sign } }
    }
}
