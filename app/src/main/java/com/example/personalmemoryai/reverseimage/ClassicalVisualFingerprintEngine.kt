package com.example.personalmemoryai.reverseimage

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.AKAZE
import org.opencv.features2d.BFMatcher
import org.opencv.imgproc.Imgproc

/**
 * Classical, non-neural visual fingerprint stack for the standalone reverse-image engine.
 *
 * Layers:
 * - pHash + dHash for perceptual/near-duplicate evidence.
 * - HSV histogram for color distribution.
 * - Sobel edge-direction/density signature for shape/structure.
 * - AKAZE local descriptors + ratio test + RANSAC homography for crop/perspective/overlay robustness.
 *
 * This engine never calls MobileCLIP or any neural network.
 */
class ClassicalVisualFingerprintEngine {

    companion object {
        const val ENGINE_VERSION = "CLASSICAL-PHASH-DHASH-HSV-SOBEL-AKAZE-V1"
        private const val HASH_SIZE = 32
        private const val PHASH_LOW = 8
        private const val COLOR_BINS = 48
        private const val EDGE_BINS = 32
        private const val MAX_KEYPOINTS = 300
        private const val RATIO_TEST = 0.78f
        private const val RANSAC_REPROJECTION = 5.0
    }

    data class Fingerprint(
        val phash: Long,
        val dhash: Long,
        val colorHistogram: ByteArray,
        val edgeHistogram: ByteArray,
        val keypoints: ByteArray?,
        val descriptors: ByteArray?,
        val descriptorRows: Int,
        val descriptorCols: Int,
        val descriptorType: Int
    )

    data class Score(
        val similarity: Float,
        val phashSimilarity: Float,
        val dhashSimilarity: Float,
        val colorSimilarity: Float,
        val edgeSimilarity: Float,
        val localSimilarity: Float,
        val localMatches: Int,
        val ransacInliers: Int
    )

    private data class LocalData(
        val keypoints: Array<org.opencv.core.KeyPoint>,
        val descriptors: Mat
    )

    fun fingerprint(bitmap: Bitmap): Fingerprint {
        val gray = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
        return try {
            val pixels = IntArray(HASH_SIZE * HASH_SIZE)
            gray.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
            val luminance = DoubleArray(pixels.size)
            for (index in pixels.indices) {
                val r = ((pixels[index] ushr 16) and 0xFF).toDouble()
                val g = ((pixels[index] ushr 8) and 0xFF).toDouble()
                val b = (pixels[index] and 0xFF).toDouble()
                luminance[index] = 0.299 * r + 0.587 * g + 0.114 * b
            }
            val phash = perceptualHash(luminance)
            val dhash = differenceHash(luminance)
            val color = colorHistogram(bitmap)
            val edges = edgeHistogram(luminance)
            val local = extractLocalFeatures(bitmap)
            Fingerprint(
                phash = phash,
                dhash = dhash,
                colorHistogram = color,
                edgeHistogram = edges,
                keypoints = local?.let(::serializeKeypoints),
                descriptors = local?.descriptors?.let(::serializeMatBytes),
                descriptorRows = local?.descriptors?.rows() ?: 0,
                descriptorCols = local?.descriptors?.cols() ?: 0,
                descriptorType = local?.descriptors?.type() ?: CvType.CV_8U
            )
        } finally {
            gray.recycle()
        }
    }

    fun compare(query: Fingerprint, target: Fingerprint): Score {
        val phash = 1f - java.lang.Long.bitCount(query.phash xor target.phash) / 64f
        val dhash = 1f - java.lang.Long.bitCount(query.dhash xor target.dhash) / 64f
        val color = histogramIntersection(query.colorHistogram, target.colorHistogram)
        val edge = histogramIntersection(query.edgeHistogram, target.edgeHistogram)

        var local = 0f
        var localMatches = 0
        var ransacInliers = 0
        if (query.keypoints != null && query.descriptors != null && target.keypoints != null && target.descriptors != null && target.descriptorRows > 0) {
            val localResult = localMatch(query, target)
            local = localResult.first
            localMatches = localResult.second
            ransacInliers = localResult.third
        }

        // Evidence weights intentionally favor structural fingerprints and verified local geometry.
        val localWeight = if (query.keypoints != null && target.keypoints != null) 0.25f else 0f
        val remaining = 1f - localWeight
        val global = (
            phash * 0.25f +
                dhash * 0.15f +
                color * 0.35f +
                edge * 0.25f
            )
        val similarity = if (localWeight > 0f) {
            global * remaining + local * localWeight
        } else {
            global
        }
        return Score(similarity.coerceIn(0f, 1f), phash, dhash, color, edge, local, localMatches, ransacInliers)
    }

    private fun perceptualHash(data: DoubleArray): Long {
        val dct = DoubleArray(PHASH_LOW * PHASH_LOW)
        val n = HASH_SIZE
        val c = DoubleArray(n) { u -> if (u == 0) 1.0 / sqrt(n.toDouble()) else sqrt(2.0 / n) }
        for (u in 0 until PHASH_LOW) {
            for (v in 0 until PHASH_LOW) {
                var sum = 0.0
                for (x in 0 until n) {
                    for (y in 0 until n) {
                        sum += data[x * n + y] *
                            cos(((2 * x + 1) * u * Math.PI) / (2 * n)) *
                            cos(((2 * y + 1) * v * Math.PI) / (2 * n))
                    }
                }
                dct[u * PHASH_LOW + v] = c[u] * c[v] * sum
            }
        }
        val values = dct.drop(1)
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        var hash = 0L
        var bit = 0
        for (value in dct) {
            if (value > median) hash = hash or (1L shl bit)
            bit++
            if (bit == 64) break
        }
        return hash
    }

    private fun differenceHash(data: DoubleArray): Long {
        var hash = 0L
        var bit = 0
        for (row in 0 until HASH_SIZE) {
            for (col in 0 until HASH_SIZE - 1) {
                if (data[row * HASH_SIZE + col] > data[row * HASH_SIZE + col + 1]) hash = hash or (1L shl bit)
                bit++
                if (bit == 64) return hash
            }
        }
        return hash
    }

    private fun colorHistogram(bitmap: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        return try {
            val bins = FloatArray(COLOR_BINS)
            val pixels = IntArray(32 * 32)
            scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            for (pixel in pixels) {
                val r = ((pixel ushr 16) and 0xFF) / 255f
                val g = ((pixel ushr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val maxC = max(r, max(g, b))
                val minC = minOf(r, g, b)
                val delta = maxC - minC
                var hue = 0f
                if (delta > 1e-6f) {
                    hue = when (maxC) {
                        r -> ((g - b) / delta) % 6f
                        g -> (b - r) / delta + 2f
                        else -> (r - g) / delta + 4f
                    } / 6f
                    if (hue < 0f) hue += 1f
                }
                val saturation = if (maxC <= 1e-6f) 0f else delta / maxC
                val value = maxC
                val hBin = (hue * 16).toInt().coerceIn(0, 15)
                val sBin = (saturation * 3).toInt().coerceIn(0, 2)
                val vBin = (value * 1).toInt().coerceIn(0, 0)
                bins[hBin * 3 + sBin] += 1f
            }
            val maxValue = bins.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            ByteArray(COLOR_BINS) { i -> (bins[i] / maxValue * 255f).roundToByte() }
        } finally {
            scaled.recycle()
        }
    }

    private fun edgeHistogram(data: DoubleArray): ByteArray {
        val bins = FloatArray(EDGE_BINS)
        val cell = 8
        for (y in 1 until HASH_SIZE - 1) {
            for (x in 1 until HASH_SIZE - 1) {
                val gx = data[y * HASH_SIZE + (x + 1)] - data[y * HASH_SIZE + (x - 1)]
                val gy = data[(y + 1) * HASH_SIZE + x] - data[(y - 1) * HASH_SIZE + x]
                val mag = sqrt(gx * gx + gy * gy)
                if (mag < 18.0) continue
                var angle = Math.atan2(gy, gx)
                if (angle < 0) angle += Math.PI
                val direction = ((angle / Math.PI) * 8).toInt().coerceIn(0, 7)
                val cellIndex = ((y / cell) * 4 + (x / cell)).coerceIn(0, 15)
                bins[cellIndex * 2 + (direction and 1)] += mag.toFloat()
            }
        }
        val maxValue = bins.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        return ByteArray(EDGE_BINS) { i -> (bins[i] / maxValue * 255f).roundToByte() }
    }

    private fun extractLocalFeatures(bitmap: Bitmap): LocalData? {
        if (!OpenCVLoader.initLocal()) return null
        val rgba = Mat()
        val gray = Mat()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        return try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val detector = AKAZE.create()
            detector.detectAndCompute(gray, Mat(), keypoints, descriptors)
            if (descriptors.empty() || keypoints.empty()) return null
            val keyArray = keypoints.toArray()
                .sortedByDescending { it.response }
                .take(MAX_KEYPOINTS)
            val allowed = keyArray.toSet()
            val selected = MatOfKeyPoint()
            selected.fromList(keyArray.toList())
            val selectedDescriptors = Mat()
            detector.compute(gray, selected, selectedDescriptors)
            if (selectedDescriptors.empty() || selected.empty() || !selectedDescriptors.isContinuous) return null
            LocalData(keyArray, selectedDescriptors.clone())
        } catch (_: Throwable) {
            null
        } finally {
            rgba.release(); gray.release(); keypoints.release(); descriptors.release()
        }
    }

    private fun localMatch(query: Fingerprint, target: Fingerprint): Triple<Float, Int, Int> {
        if (query.keypoints == null || target.keypoints == null || query.descriptors == null || target.descriptors == null) return Triple(0f, 0, 0)
        return try {
            if (!OpenCVLoader.initLocal()) return Triple(0f, 0, 0)
            val qDesc = deserializeMat(query.descriptors, queryDescriptorRows(query), queryDescriptorCols(query), CvType.CV_8U)
            val tDesc = deserializeMat(target.descriptors, targetDescriptorRows(target), targetDescriptorCols(target), target.descriptorType)
            if (qDesc.empty() || tDesc.empty()) return Triple(0f, 0, 0)
            val matcher = BFMatcher.create(org.opencv.core.Core.NORM_HAMMING, false)
            val knn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(qDesc, tDesc, knn, 2)
            val good = ArrayList<org.opencv.core.DMatch>()
            for (pair in knn) {
                val matches = pair.toArray()
                if (matches.size >= 2 && matches[0].distance < RATIO_TEST * matches[1].distance) good += matches[0]
                pair.release()
            }
            if (good.size < 4) return Triple((good.size / 30f).coerceIn(0f, 1f), good.size, 0)

            val qPoints = deserializeKeypoints(query.keypoints)
            val tPoints = deserializeKeypoints(target.keypoints)
            val src = ArrayList<Point>(good.size)
            val dst = ArrayList<Point>(good.size)
            for (match in good) {
                if (match.queryIdx in qPoints.indices && match.trainIdx in tPoints.indices) {
                    src += qPoints[match.queryIdx]
                    dst += tPoints[match.trainIdx]
                }
            }
            if (src.size < 4) return Triple((good.size / 30f).coerceIn(0f, 1f), good.size, 0)
            val srcMat = MatOfPoint2f(*src.toTypedArray())
            val dstMat = MatOfPoint2f(*dst.toTypedArray())
            val mask = Mat()
            Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, RANSAC_REPROJECTION, mask)
            val inliers = if (!mask.empty()) org.opencv.core.Core.countNonZero(mask) else 0
            val similarity = (inliers / 25f).coerceIn(0f, 1f)
            srcMat.release(); dstMat.release(); mask.release(); qDesc.release(); tDesc.release()
            Triple(similarity, good.size, inliers)
        } catch (_: Throwable) {
            Triple(0f, 0, 0)
        }
    }

    private fun queryDescriptorRows(fp: Fingerprint): Int = fp.descriptorRows
    private fun queryDescriptorCols(fp: Fingerprint): Int = fp.descriptorCols
    private fun targetDescriptorRows(fp: Fingerprint): Int = fp.descriptorRows
    private fun targetDescriptorCols(fp: Fingerprint): Int = fp.descriptorCols

    private fun deserializeMat(bytes: ByteArray, rows: Int, cols: Int, type: Int): Mat {
        val mat = Mat(rows, cols, type)
        mat.put(0, 0, bytes)
        return mat
    }

    private fun serializeMatBytes(mat: Mat): ByteArray {
        val buffer = ByteArray(mat.total().toInt() * mat.elemSize().toInt())
        mat.get(0, 0, buffer)
        return buffer
    }

    private fun serializeKeypoints(keypoints: LocalData): ByteArray {
        val buffer = ByteBuffer.allocate(4 + keypoints.keypoints.size * 8).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(keypoints.keypoints.size)
        keypoints.keypoints.forEach { buffer.putFloat(it.pt.x.toFloat()); buffer.putFloat(it.pt.y.toFloat()) }
        return buffer.array()
    }

    private fun deserializeKeypoints(bytes: ByteArray): Array<Point> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.int.coerceAtLeast(0)
        return Array(count) { Point(buffer.float.toDouble(), buffer.float.toDouble()) }
    }

    private fun histogramIntersection(a: ByteArray, b: ByteArray): Float {
        val count = minOf(a.size, b.size)
        if (count == 0) return 0f
        var numerator = 0.0
        var denom = 0.0
        for (i in 0 until count) {
            val av = (a[i].toInt() and 0xFF) / 255.0
            val bv = (b[i].toInt() and 0xFF) / 255.0
            numerator += minOf(av, bv)
            denom += maxOf(av, bv)
        }
        return if (denom == 0.0) 1f else (numerator / denom).toFloat().coerceIn(0f, 1f)
    }

    private fun Float.roundToByte(): Byte = kotlin.math.round(this).toInt().coerceIn(0, 255).toByte()
}
