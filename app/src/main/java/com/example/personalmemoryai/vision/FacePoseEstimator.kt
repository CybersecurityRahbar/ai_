package com.example.personalmemoryai.vision

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightweight pose estimator derived from the MediaPipe landmark geometry.
 * It is an auxiliary visual signal, not a calibrated camera pose measurement.
 */
object FacePoseEstimator {
    data class Pose(val pitch: Float, val yaw: Float, val roll: Float)

    // Stable MediaPipe Face Mesh indices: eye corners, nose tip and mouth center.
    private const val LEFT_EYE_OUTER = 33
    private const val LEFT_EYE_INNER = 133
    private const val RIGHT_EYE_INNER = 362
    private const val RIGHT_EYE_OUTER = 263
    private const val NOSE_TIP = 1
    private const val UPPER_LIP = 13
    private const val LOWER_LIP = 14

    fun estimate(result: FaceLandmarkResult): Pose? {
        val p = result.landmarks
        val required = intArrayOf(
            LEFT_EYE_OUTER, LEFT_EYE_INNER, RIGHT_EYE_INNER, RIGHT_EYE_OUTER,
            NOSE_TIP, UPPER_LIP, LOWER_LIP
        )
        if (required.any { it !in p.indices }) return null

        val leftEye = midpoint(p[LEFT_EYE_OUTER], p[LEFT_EYE_INNER])
        val rightEye = midpoint(p[RIGHT_EYE_INNER], p[RIGHT_EYE_OUTER])
        val eyeDx = rightEye.x - leftEye.x
        val eyeDy = rightEye.y - leftEye.y
        val eyeDistance = sqrt(eyeDx * eyeDx + eyeDy * eyeDy).coerceAtLeast(1e-5f)
        val eyeMid = midpoint(leftEye, rightEye)
        val nose = p[NOSE_TIP]
        val mouth = midpoint(p[UPPER_LIP], p[LOWER_LIP])

        // Roll is the angle of the inter-eye line.
        val roll = Math.toDegrees(atan2(eyeDy.toDouble(), eyeDx.toDouble())).toFloat()

        // Yaw proxy: horizontal nose displacement relative to inter-eye distance.
        // The scale is intentionally conservative because this is a landmark-derived proxy.
        val normalizedNoseX = (nose.x - eyeMid.x) / eyeDistance
        val yaw = (normalizedNoseX * 45f).coerceIn(-90f, 90f)

        // Pitch proxy: nose/mouth vertical structure relative to the eye-to-mouth span.
        val faceVertical = (mouth.y - eyeMid.y).coerceAtLeast(1e-5f)
        val normalizedNoseY = (nose.y - eyeMid.y) / faceVertical
        val pitch = ((normalizedNoseY - 0.48f) * 55f).coerceIn(-90f, 90f)

        return Pose(pitch, yaw, roll)
    }

    fun similarity(first: Pose?, second: Pose?): Float {
        if (first == null || second == null) return 0f
        val pitch = angularSimilarity(first.pitch, second.pitch, 30f)
        val yaw = angularSimilarity(first.yaw, second.yaw, 30f)
        val roll = angularSimilarity(first.roll, second.roll, 25f)
        return (0.35f * pitch + 0.45f * yaw + 0.20f * roll).coerceIn(0f, 1f)
    }

    private fun angularSimilarity(a: Float, b: Float, tolerance: Float): Float =
        (1f - abs(a - b) / tolerance).coerceIn(0f, 1f)

    private fun midpoint(a: FaceLandmarkResult.Point, b: FaceLandmarkResult.Point): FaceLandmarkResult.Point =
        FaceLandmarkResult.Point((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f, (a.z + b.z) * 0.5f)
}
