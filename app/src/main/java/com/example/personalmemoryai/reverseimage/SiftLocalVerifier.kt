package com.example.personalmemoryai.reverseimage

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc

/**
 * Classical SIFT verifier used only on the staged-search shortlist.
 * It is intentionally not persisted: the durable index keeps the lighter AKAZE descriptors.
 */
class SiftLocalVerifier {
    companion object {
        private const val MAX_FEATURES = 220
        private const val RATIO_TEST = 0.72f
        private const val RANSAC_REPROJECTION = 4.0
        private const val MIN_INLIERS = 4
    }

    data class Score(
        val similarity: Float,
        val goodMatches: Int,
        val inliers: Int
    )

    fun compare(query: Bitmap, target: Bitmap): Score {
        if (!OpenCVLoader.initLocal()) return Score(0f, 0, 0)
        val queryRgba = Mat()
        val targetRgba = Mat()
        val queryGray = Mat()
        val targetGray = Mat()
        val queryKeypoints = MatOfKeyPoint()
        val targetKeypoints = MatOfKeyPoint()
        val queryDescriptors = Mat()
        val targetDescriptors = Mat()
        var srcMat: MatOfPoint2f? = null
        var dstMat: MatOfPoint2f? = null
        var mask: Mat? = null
        return try {
            Utils.bitmapToMat(query, queryRgba)
            Utils.bitmapToMat(target, targetRgba)
            Imgproc.cvtColor(queryRgba, queryGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(targetRgba, targetGray, Imgproc.COLOR_RGBA2GRAY)

            val sift = SIFT.create(MAX_FEATURES)
            sift.detectAndCompute(queryGray, Mat(), queryKeypoints, queryDescriptors)
            sift.detectAndCompute(targetGray, Mat(), targetKeypoints, targetDescriptors)
            if (queryKeypoints.empty() || targetKeypoints.empty() ||
                queryDescriptors.empty() || targetDescriptors.empty()) {
                return Score(0f, 0, 0)
            }

            val matcher = BFMatcher.create(org.opencv.core.Core.NORM_L2, false)
            val forward = ArrayList<MatOfDMatch>()
            val reverse = ArrayList<MatOfDMatch>()
            matcher.knnMatch(queryDescriptors, targetDescriptors, forward, 2)
            matcher.knnMatch(targetDescriptors, queryDescriptors, reverse, 2)

            val forwardBest = HashMap<Int, org.opencv.core.DMatch>()
            val reverseBest = HashMap<Int, Int>()
            for (pair in forward) {
                val matches = pair.toArray()
                if (matches.size >= 2 && matches[0].distance < RATIO_TEST * matches[1].distance) {
                    forwardBest[matches[0].queryIdx] = matches[0]
                }
                pair.release()
            }
            for (pair in reverse) {
                val matches = pair.toArray()
                if (matches.size >= 2 && matches[0].distance < RATIO_TEST * matches[1].distance) {
                    reverseBest[matches[0].queryIdx] = matches[0].trainIdx
                }
                pair.release()
            }

            val good = forwardBest.values.filter { reverseBest[it.trainIdx] == it.queryIdx }
            if (good.size < MIN_INLIERS) return Score(0f, good.size, 0)

            val queryPoints = queryKeypoints.toArray().map { Point(it.pt.x, it.pt.y) }
            val targetPoints = targetKeypoints.toArray().map { Point(it.pt.x, it.pt.y) }
            val src = ArrayList<Point>(good.size)
            val dst = ArrayList<Point>(good.size)
            for (match in good) {
                if (match.queryIdx in queryPoints.indices && match.trainIdx in targetPoints.indices) {
                    src += queryPoints[match.queryIdx]
                    dst += targetPoints[match.trainIdx]
                }
            }
            if (src.size < MIN_INLIERS) return Score(0f, good.size, 0)

            srcMat = MatOfPoint2f(*src.toTypedArray())
            dstMat = MatOfPoint2f(*dst.toTypedArray())
            mask = Mat()
            Calib3d.findHomography(srcMat, dstMat, Calib3d.RANSAC, RANSAC_REPROJECTION, mask)
            val inliers = if (!mask.empty()) org.opencv.core.Core.countNonZero(mask) else 0
            if (inliers < MIN_INLIERS) return Score(0f, good.size, inliers)

            val inlierRatio = inliers.toFloat() / good.size.toFloat().coerceAtLeast(1f)
            val inlierEvidence = (inliers / 18f).coerceIn(0f, 1f)
            val coverageEvidence = (good.size / 36f).coerceIn(0f, 1f)
            val similarity = (inlierEvidence * 0.55f + inlierRatio * 0.30f + coverageEvidence * 0.15f)
                .coerceIn(0f, 1f)
            Score(similarity, good.size, inliers)
        } catch (_: Throwable) {
            Score(0f, 0, 0)
        } finally {
            queryRgba.release()
            targetRgba.release()
            queryGray.release()
            targetGray.release()
            queryKeypoints.release()
            targetKeypoints.release()
            queryDescriptors.release()
            targetDescriptors.release()
            srcMat?.release()
            dstMat?.release()
            mask?.release()
        }
    }
}
