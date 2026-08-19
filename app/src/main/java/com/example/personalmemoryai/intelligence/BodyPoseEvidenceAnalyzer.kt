package com.example.personalmemoryai.intelligence

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.sqrt

/**
 * On-device body/pose evidence for static images.
 * Uses ML Kit's bundled pose model and produces a normalized 33-landmark
 * descriptor plus body proportions and visibility. It is evidence, not identity proof.
 */
class BodyPoseEvidenceAnalyzer(context: Context) : AutoCloseable {
    data class Result(
        val descriptor: FloatArray,
        val landmarkCount: Int,
        val averageInFrameConfidence: Float,
        val bodyAspectRatio: Float,
        val shoulderWidth: Float,
        val hipWidth: Float,
        val available: Boolean
    )

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
    )

    suspend fun analyze(uri: Uri): Result? {
        val image = try { InputImage.fromFilePath(context, uri) } catch (_: Throwable) { return null }
        return try {
            analyze(detector.process(image).await(), image.width, image.height)
        } catch (_: Throwable) {
            null
        }
    }

    private fun analyze(pose: Pose, width: Int, height: Int): Result? {
        val landmarks = pose.getAllPoseLandmarks()
        if (landmarks.isEmpty() || width <= 0 || height <= 0) return null
        val byType = landmarks.associateBy { it.landmarkType }
        val descriptor = FloatArray(33 * 4)
        for (type in 0..32) {
            val landmark = byType[type]
            val base = type * 4
            if (landmark == null) {
                descriptor[base] = -1f
                descriptor[base + 1] = -1f
                descriptor[base + 2] = -1f
                descriptor[base + 3] = 0f
            } else {
                descriptor[base] = landmark.position.x / width.toFloat()
                descriptor[base + 1] = landmark.position.y / height.toFloat()
                descriptor[base + 2] = landmark.position3D?.z ?: 0f
                descriptor[base + 3] = landmark.inFrameLikelihood.coerceIn(0f, 1f)
            }
        }

        // Normalize the pose around the hip center and shoulder/hip scale.
        val leftHip = byType[PoseLandmark.LEFT_HIP]
        val rightHip = byType[PoseLandmark.RIGHT_HIP]
        val leftShoulder = byType[PoseLandmark.LEFT_SHOULDER]
        val rightShoulder = byType[PoseLandmark.RIGHT_SHOULDER]
        val hipCenterX = if (leftHip != null && rightHip != null) (leftHip.position.x + rightHip.position.x) / (2f * width) else 0.5f
        val hipCenterY = if (leftHip != null && rightHip != null) (leftHip.position.y + rightHip.position.y) / (2f * height) else 0.5f
        val shoulderWidth = distance(leftShoulder, rightShoulder, width, height)
        val hipWidth = distance(leftHip, rightHip, width, height)
        val scale = maxOf(shoulderWidth, hipWidth, 0.05f)
        for (type in 0..32) {
            val base = type * 4
            if (descriptor[base + 3] <= 0f) continue
            descriptor[base] = (descriptor[base] - hipCenterX) / scale
            descriptor[base + 1] = (descriptor[base + 1] - hipCenterY) / scale
        }

        val top = landmarks.minOf { it.position.y }
        val bottom = landmarks.maxOf { it.position.y }
        val bodyAspect = ((bottom - top) / width.toFloat()).coerceIn(0f, 10f)
        val avg = landmarks.map { it.inFrameLikelihood }.average().toFloat().coerceIn(0f, 1f)
        return Result(descriptor, landmarks.size, avg, bodyAspect, shoulderWidth, hipWidth, true)
    }

    fun similarity(a: Result, b: Result): Float {
        if (!a.available || !b.available) return 0f
        var sum = 0f
        var weight = 0f
        var i = 0
        while (i < a.descriptor.size) {
            val ax = a.descriptor[i]; val ay = a.descriptor[i + 1]; val az = a.descriptor[i + 2]
            val bx = b.descriptor[i]; val by = b.descriptor[i + 1]; val bz = b.descriptor[i + 2]
            val ac = a.descriptor[i + 3]; val bc = b.descriptor[i + 3]
            if (ac > 0f && bc > 0f && ax >= 0f && bx >= 0f) {
                val d = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz))
                val s = (1f - (d / 3f)).coerceIn(0f, 1f)
                val w = (ac * bc).coerceIn(0f, 1f)
                sum += s * w; weight += w
            }
            i += 4
        }
        if (weight <= 0f) return 0f
        val proportions = (1f - (abs(a.bodyAspectRatio - b.bodyAspectRatio) / 2f)).coerceIn(0f, 1f)
        return (sum / weight * 0.85f + proportions * 0.15f).coerceIn(0f, 1f)
    }

    private fun distance(a: PoseLandmark?, b: PoseLandmark?, width: Int, height: Int): Float {
        if (a == null || b == null) return 0f
        val dx = (a.position.x - b.position.x) / width.toFloat()
        val dy = (a.position.y - b.position.y) / height.toFloat()
        return sqrt(dx * dx + dy * dy)
    }

    override fun close() { detector.close() }
}
