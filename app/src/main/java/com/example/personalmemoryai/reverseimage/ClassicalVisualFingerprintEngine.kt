package com.example.personalmemoryai.reverseimage

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.AKAZE
import org.opencv.features2d.BFMatcher
import org.opencv.imgproc.Imgproc

/** Classical, non-neural visual fingerprint stack for local reverse-image search. */
class ClassicalVisualFingerprintEngine {
    companion object {
        const val ENGINE_VERSION = "CLASSICAL-PHASH-DHASH-HSV256-SOBEL-AKAZE-V4"
        private const val PHASH_SIZE = 32
        private const val PHASH_LOW = 8
        private const val DHASH_WIDTH = 9
        private const val DHASH_HEIGHT = 8
        private const val H_BINS = 16
        private const val S_BINS = 4
        private const val V_BINS = 4
        private const val COLOR_BINS = H_BINS * S_BINS * V_BINS
        private const val EDGE_BINS = 128
        private const val MAX_KEYPOINTS = 300
        private const val RATIO_TEST = 0.74f
        private const val RANSAC_REPROJECTION = 4.0
        private const val MIN_GEOMETRIC_INLIERS = 4
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
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0)
        val gray = Bitmap.createScaledBitmap(bitmap, PHASH_SIZE, PHASH_SIZE, true)
        return try {
            val pixels = IntArray(PHASH_SIZE * PHASH_SIZE)
            gray.getPixels(pixels, 0, PHASH_SIZE, 0, 0, PHASH_SIZE, PHASH_SIZE)
            val luminance = DoubleArray(pixels.size)
            for (index in pixels.indices) {
                val r = ((pixels[index] ushr 16) and 0xFF).toDouble()
                val g = ((pixels[index] ushr 8) and 0xFF).toDouble()
                val b = (pixels[index] and 0xFF).toDouble()
                luminance[index] = 0.299 * r + 0.587 * g + 0.114 * b
            }
            val local = extractLocalFeatures(bitmap)
            Fingerprint(
                phash = perceptualHash(luminance),
                dhash = differenceHash(bitmap),
                colorHistogram = colorHistogram(bitmap),
                edgeHistogram = edgeHistogram(luminance),
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

    fun compare(query: Fingerprint, target: Fingerprint, runLocal: Boolean = true): Score {
        val phash = 1f - java.lang.Long.bitCount(query.phash xor target.phash) / 63f
        val dhash = 1f - java.lang.Long.bitCount(query.dhash xor target.dhash) / 64f
        val color = histogramIntersection(query.colorHistogram, target.colorHistogram)
        val edge = histogramIntersection(query.edgeHistogram, target.edgeHistogram)
        val global = (phash * 0.30f + dhash * 0.20f + color * 0.25f + edge * 0.25f).coerceIn(0f, 1f)
        if (!runLocal) return Score(global, phash, dhash, color, edge, 0f, 0, 0)

        val localResult = if (query.keypoints != null && query.descriptors != null &&
            target.keypoints != null && target.descriptors != null &&
            query.descriptorRows > 0 && target.descriptorRows > 0) {
            localMatch(query, target)
        } else Triple(0f, 0, 0)

        val local = localResult.first
        val localMatches = localResult.second
        val inliers = localResult.third
        val similarity = if (inliers >= MIN_GEOMETRIC_INLIERS) {
            (global * 0.80f + local * 0.20f).coerceIn(0f, 1f)
        } else {
            global
        }
        return Score(similarity, phash, dhash, color, edge, local, localMatches, inliers)
    }

    fun compareBest(queries: List<Fingerprint>, target: Fingerprint, runLocal: Boolean = false): Score {
        require(queries.isNotEmpty()) { "At least one query fingerprint is required" }
        var best = compare(queries.first(), target, runLocal)
        for (index in 1 until queries.size) {
            val score = compare(queries[index], target, runLocal)
            if (score.similarity > best.similarity) best = score
        }
        return best
    }

    private fun perceptualHash(data: DoubleArray): Long {
        val n = PHASH_SIZE
        val dct = DoubleArray(PHASH_LOW * PHASH_LOW)
        val norm = DoubleArray(PHASH_LOW) { u -> if (u == 0) 1.0 / sqrt(n.toDouble()) else sqrt(2.0 / n) }
        for (u in 0 until PHASH_LOW) {
            for (v in 0 until PHASH_LOW) {
                var sum = 0.0
                for (x in 0 until n) {
                    val ux = ((2 * x + 1) * u * Math.PI) / (2 * n)
                    for (y in 0 until n) {
                        val vy = ((2 * y + 1) * v * Math.PI) / (2 * n)
                        sum += data[x * n + y] * cos(ux) * cos(vy)
                    }
                }
                dct[u * PHASH_LOW + v] = norm[u] * norm[v] * sum
            }
        }
        val low = dct.drop(1)
        val median = low.sorted()[low.size / 2]
        var hash = 0L
        for (bit in 0 until minOf(63, low.size)) if (low[bit] > median) hash = hash or (1L shl bit)
        return hash
    }

    private fun differenceHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, DHASH_WIDTH, DHASH_HEIGHT, true)
        return try {
            val pixels = IntArray(DHASH_WIDTH * DHASH_HEIGHT)
            scaled.getPixels(pixels, 0, DHASH_WIDTH, 0, 0, DHASH_WIDTH, DHASH_HEIGHT)
            var hash = 0L
            var bit = 0
            for (row in 0 until DHASH_HEIGHT) {
                val offset = row * DHASH_WIDTH
                for (col in 0 until DHASH_WIDTH - 1) {
                    val left = pixels[offset + col]
                    val right = pixels[offset + col + 1]
                    val ll = 0.299 * ((left ushr 16) and 0xFF) + 0.587 * ((left ushr 8) and 0xFF) + 0.114 * (left and 0xFF)
                    val rr = 0.299 * ((right ushr 16) and 0xFF) + 0.587 * ((right ushr 8) and 0xFF) + 0.114 * (right and 0xFF)
                    if (ll > rr) hash = hash or (1L shl bit)
                    bit++
                }
            }
            hash
        } finally {
            scaled.recycle()
        }
    }

    /** L1-normalized full HSV distribution: 16 hue × 4 saturation × 4 value bins. */
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
                val hue = if (delta <= 1e-6f) 0f else {
                    val raw = when (maxC) {
                        r -> ((g - b) / delta) % 6f
                        g -> (b - r) / delta + 2f
                        else -> (r - g) / delta + 4f
                    } / 6f
                    if (raw < 0f) raw + 1f else raw
                }
                val saturation = if (maxC <= 1e-6f) 0f else delta / maxC
                val h = (hue * H_BINS).toInt().coerceIn(0, H_BINS - 1)
                val s = (saturation * S_BINS).toInt().coerceIn(0, S_BINS - 1)
                val v = (maxC * V_BINS).toInt().coerceIn(0, V_BINS - 1)
                bins[(h * S_BINS + s) * V_BINS + v] += 1f
            }
            normalizeHistogram(bins)
        } finally {
            scaled.recycle()
        }
    }

    private fun edgeHistogram(data: DoubleArray): ByteArray {
        val bins = FloatArray(EDGE_BINS)
        val cellSize = 8
        for (y in 1 until PHASH_SIZE - 1) {
            for (x in 1 until PHASH_SIZE - 1) {
                val gx = data[y * PHASH_SIZE + x + 1] - data[y * PHASH_SIZE + x - 1]
                val gy = data[(y + 1) * PHASH_SIZE + x] - data[(y - 1) * PHASH_SIZE + x]
                val magnitude = sqrt(gx * gx + gy * gy)
                if (magnitude < 12.0) continue
                var angle = kotlin.math.atan2(gy, gx)
                if (angle < 0) angle += Math.PI
                val direction = ((angle / Math.PI) * 8.0).toInt().coerceIn(0, 7)
                val cellX = (x / cellSize).coerceIn(0, 3)
                val cellY = (y / cellSize).coerceIn(0, 3)
                bins[(cellY * 4 + cellX) * 8 + direction] += magnitude.toFloat()
            }
        }
        return normalizeHistogram(bins)
    }

    private fun normalizeHistogram(bins: FloatArray): ByteArray {
        val total = bins.sum().coerceAtLeast(1e-6f)
        return ByteArray(bins.size) { i -> ((bins[i] / total) * 255f).toInt().coerceIn(0, 255).toByte() }
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
            if (keypoints.empty() || descriptors.empty()) return null
            val all = keypoints.toArray()
            val selectedIndices = all.indices.sortedByDescending { all[it].response }.take(MAX_KEYPOINTS)
            if (selectedIndices.size < 4) return null
            val selectedKeypoints = Array(selectedIndices.size) { all[selectedIndices[it]] }
            val selectedDescriptors = Mat(selectedIndices.size, descriptors.cols(), descriptors.type())
            for (row in selectedIndices.indices) descriptors.row(selectedIndices[row]).copyTo(selectedDescriptors.row(row))
            LocalData(selectedKeypoints, selectedDescriptors)
        } catch (_: Throwable) {
            null
        } finally {
            rgba.release()
            gray.release()
            keypoints.release()
            descriptors.release()
        }
    }

    private fun localMatch(query: Fingerprint, target: Fingerprint): Triple<Float, Int, Int> {
        if (query.keypoints == null || query.descriptors == null || target.keypoints == null || target.descriptors == null) return Triple(0f, 0, 0)
        var qDesc: Mat? = null
        var tDesc: Mat? = null
        var srcMat: MatOfPoint2f? = null
        var dstMat: MatOfPoint2f? = null
        var mask: Mat? = null
        return try {
            if (!OpenCVLoader.initLocal()) return Triple(0f, 0, 0)
            qDesc = deserializeMat(query.descriptors, query.descriptorRows, query.descriptorCols, query.descriptorType)
            tDesc = deserializeMat(target.descriptors, target.descriptorRows, target.descriptorCols, target.descriptorType)
            if (qDesc.empty() || tDesc.empty()) return Triple(0f, 0, 0)
            val matcher = BFMatcher.create(org.opencv.core.Core.NORM_HAMMING, false)
            val forwardKnn = ArrayList<MatOfDMatch>()
            val reverseKnn = ArrayList<MatOfDMatch>()
            matcher.knnMatch(qDesc, tDesc, forwardKnn, 2)
            matcher.knnMatch(tDesc, qDesc, reverseKnn, 2)
            val forwardBest = HashMap<Int, org.opencv.core.DMatch>()
            for (pair in forwardKnn) {
                val m = pair.toArray()
                if (m.size >= 2 && m[0].distance < RATIO_TEST * m[1].distance) forwardBest[m[0].queryIdx] = m[0]
                pair.release()
            }
            val reverseBest = HashMap<Int, Int>()
            for (pair in reverseKnn) {
                val m = pair.toArray()
                if (m.size >= 2 && m[0].distance < RATIO_TEST * m[1].distance) reverseBest[m[0].queryIdx] = m[0].trainIdx
                pair.release()
            }
            val good = forwardBest.values.filter { reverseBest[it.trainIdx] == it.queryIdx }
            if (good.size < MIN_GEOMETRIC_INLIERS) return Triple(0f, good.size, 0)
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
            if (src.size < MIN_GEOMETRIC_INLIERS) return Triple(0f, good.size, 0)
            srcMat = MatOfPoint2f(*src.toTypedArray())
            dstMat = MatOfPoint2f(*dst.toTypedArray())
            mask = Mat()
            Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, RANSAC_REPROJECTION, mask)
            val inliers = if (!mask.empty()) org.opencv.core.Core.countNonZero(mask) else 0
            if (inliers < MIN_GEOMETRIC_INLIERS) return Triple(0f, good.size, inliers)
            val inlierRatio = inliers.toFloat() / good.size.toFloat().coerceAtLeast(1f)
            val countEvidence = (inliers / 20f).coerceIn(0f, 1f)
            val coverageEvidence = (good.size / 40f).coerceIn(0f, 1f)
            val local = (countEvidence * 0.55f + inlierRatio * 0.30f + coverageEvidence * 0.15f).coerceIn(0f, 1f)
            Triple(local, good.size, inliers)
        } catch (_: Throwable) {
            Triple(0f, 0, 0)
        } finally {
            qDesc?.release()
            tDesc?.release()
            srcMat?.release()
            dstMat?.release()
            mask?.release()
        }
    }

    private fun deserializeMat(bytes: ByteArray, rows: Int, cols: Int, type: Int): Mat {
        val mat = Mat(rows, cols, type)
        mat.put(0, 0, bytes)
        return mat
    }

    private fun serializeMatBytes(mat: Mat): ByteArray {
        val bytes = ByteArray(mat.total().toInt() * mat.elemSize().toInt())
        mat.get(0, 0, bytes)
        return bytes
    }

    private fun serializeKeypoints(keypoints: LocalData): ByteArray {
        val buffer = ByteBuffer.allocate(4 + keypoints.keypoints.size * 8).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(keypoints.keypoints.size)
        for (keypoint in keypoints.keypoints) {
            buffer.putFloat(keypoint.pt.x.toFloat())
            buffer.putFloat(keypoint.pt.y.toFloat())
        }
        return buffer.array()
    }

    private fun deserializeKeypoints(bytes: ByteArray): Array<Point> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() < 4) return emptyArray()
        val count = buffer.int.coerceAtLeast(0).coerceAtMost(10000)
        if (buffer.remaining() < count * 8) return emptyArray()
        return Array(count) { Point(buffer.float.toDouble(), buffer.float.toDouble()) }
    }

    private fun histogramIntersection(a: ByteArray, b: ByteArray): Float {
        val count = minOf(a.size, b.size)
        if (count == 0) return 0f
        var numerator = 0.0
        var denominator = 0.0
        for (i in 0 until count) {
            val av = (a[i].toInt() and 0xFF) / 255.0
            val bv = (b[i].toInt() and 0xFF) / 255.0
            numerator += minOf(av, bv)
            denominator += max(av, bv)
        }
        return if (denominator == 0.0) 1f else (numerator / denominator).toFloat().coerceIn(0f, 1f)
    }
}
