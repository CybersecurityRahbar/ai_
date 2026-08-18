package com.example.personalmemoryai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import kotlin.math.min

/** MediaPipe-based facial analysis engine. Detection/landmarks only; identity is handled separately. */
class MediaPipeFaceAnalyzer(context: Context) : AutoCloseable {

    private val landmarker: FaceLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_FILE)
            .build()

        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(MAX_FACES)
            .setMinFaceDetectionConfidence(MIN_FACE_DETECTION_CONFIDENCE)
            .setMinFacePresenceConfidence(MIN_FACE_PRESENCE_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .build()

        landmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun analyze(bitmap: Bitmap): List<FaceLandmarkResult> {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) {
            return emptyList()
        }

        val mpImage = BitmapImageBuilder(bitmap).build()
        return try {
            val result = landmarker.detect(mpImage)
            convertResult(result, bitmap.width, bitmap.height)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun convertResult(
        result: FaceLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int
    ): List<FaceLandmarkResult> {
        val output = mutableListOf<FaceLandmarkResult>()
        val faceLandmarks = result.faceLandmarks()

        if (faceLandmarks.isEmpty()) return output

        for (faceIndex in faceLandmarks.indices) {
            val mediapipeLandmarks = faceLandmarks[faceIndex]
            if (mediapipeLandmarks.isEmpty()) continue

            val points = mediapipeLandmarks.map { landmark ->
                FaceLandmarkResult.Point(
                    x = landmark.x(),
                    y = landmark.y(),
                    z = landmark.z()
                )
            }

            if (points.isEmpty()) continue

            val boundingBox = calculateBoundingBox(points, imageWidth, imageHeight)
            val blendshapes = extractBlendshapes(result, faceIndex)
            val rotation = extractRotation(result, faceIndex)
            val detectionConfidence = calculateOperationalConfidence(points)

            output += FaceLandmarkResult(
                boundingBox = boundingBox,
                landmarks = points,
                blendshapes = blendshapes,
                rotationX = rotation?.first,
                rotationY = rotation?.second,
                rotationZ = rotation?.third,
                detectionConfidence = detectionConfidence
            )
        }

        return output
    }

    /**
     * MediaPipe Tasks exposes face blendshapes as java.util.Optional.
     * The previous implementation treated Optional as a Kotlin List,
     * causing the compilation errors on .size and indexed get().
     */
    private fun extractBlendshapes(
        result: FaceLandmarkerResult,
        faceIndex: Int
    ): List<FaceLandmarkResult.Blendshape> {
        val optionalBlendshapes = result.faceBlendshapes()
        if (!optionalBlendshapes.isPresent) return emptyList()

        return try {
            val allBlendshapes = optionalBlendshapes.get()
            if (faceIndex < 0 || faceIndex >= allBlendshapes.size) {
                return emptyList()
            }

            allBlendshapes[faceIndex].mapNotNull { category ->
                val name = category.categoryName()
                val score = category.score()

                if (name.isNullOrBlank()) {
                    null
                } else {
                    FaceLandmarkResult.Blendshape(
                        name = name,
                        score = score.coerceIn(0f, 1f)
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Head-pose extraction is intentionally deferred. The transformation
     * matrix API differs between MediaPipe Tasks artifacts; returning null
     * is safer than fabricating Euler angles.
     */
    private fun extractRotation(
        result: FaceLandmarkerResult,
        faceIndex: Int
    ): Triple<Float, Float, Float>? {
        return null
    }

    private fun calculateBoundingBox(
        points: List<FaceLandmarkResult.Point>,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        if (points.isEmpty()) return RectF()

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (point in points) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }

        val left = minX.coerceIn(0f, 1f)
        val top = minY.coerceIn(0f, 1f)
        val right = maxX.coerceIn(0f, 1f)
        val bottom = maxY.coerceIn(0f, 1f)

        if (right <= left || bottom <= top) {
            return RectF(left, top, left, top)
        }

        return RectF(left, top, right, bottom)
    }

    private fun calculateOperationalConfidence(
        points: List<FaceLandmarkResult.Point>
    ): Float {
        if (points.isEmpty()) return 0f

        return (points.size.toFloat() / EXPECTED_LANDMARK_COUNT)
            .coerceIn(0f, 1f)
    }

    override fun close() {
        try {
            landmarker.close()
        } catch (_: Throwable) {
            // Ignore cleanup exceptions.
        }
    }

    companion object {
        private const val MODEL_FILE = "face_landmarker.task"
        private const val MAX_FACES = 20
        private const val MIN_FACE_DETECTION_CONFIDENCE = 0.50f
        private const val MIN_FACE_PRESENCE_CONFIDENCE = 0.50f
        private const val MIN_TRACKING_CONFIDENCE = 0.50f
        private const val EXPECTED_LANDMARK_COUNT = 478f
    }
}
