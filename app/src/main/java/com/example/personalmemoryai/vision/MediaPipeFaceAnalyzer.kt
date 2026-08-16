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

/**
 * On-device face analysis using MediaPipe Face Landmarker.
 *
 * This component performs:
 *
 * - face detection
 * - facial landmarks
 * - optional blendshapes
 * - facial transformation data
 *
 * It does NOT identify a person.
 *
 * Identity/similarity is handled separately by the
 * FaceEmbeddingModel and FaceMatchingEngine.
 */
class MediaPipeFaceAnalyzer(
    context: Context
) : AutoCloseable {

    private val landmarker: FaceLandmarker

    init {

        val baseOptions =
            BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .build()

        val options =
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(MAX_FACES)
                .setMinFaceDetectionConfidence(
                    MIN_DETECTION_CONFIDENCE
                )
                .setMinFacePresenceConfidence(
                    MIN_PRESENCE_CONFIDENCE
                )
                .setMinTrackingConfidence(
                    MIN_TRACKING_CONFIDENCE
                )
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .build()

        landmarker =
            FaceLandmarker.createFromOptions(
                context,
                options
            )
    }

    /**
     * Analyze a single bitmap.
     */
    fun analyze(
        bitmap: Bitmap
    ): List<FaceLandmarkResult> {

        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return emptyList()
        }

        val mpImage =
            BitmapImageBuilder(bitmap)
                .build()

        val result =
            landmarker.detect(mpImage)

        return convertResult(
            result,
            bitmap.width,
            bitmap.height
        )
    }

    private fun convertResult(
        result: FaceLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int
    ): List<FaceLandmarkResult> {

        val output = mutableListOf<FaceLandmarkResult>()

        val faceLandmarks =
            result.faceLandmarks()

        for (faceIndex in faceLandmarks.indices) {

            val points =
                faceLandmarks[faceIndex]
                    .map { landmark ->

                        FaceLandmarkResult.Point(
                            x = landmark.x(),
                            y = landmark.y(),
                            z = landmark.z()
                        )
                    }

            if (points.isEmpty()) {
                continue
            }

            val boundingBox =
                calculateBoundingBox(
                    points,
                    imageWidth,
                    imageHeight
                )

            val blendshapes =
                if (
                    faceIndex <
                    result.faceBlendshapes().size
                ) {

                    result.faceBlendshapes()[faceIndex]
                        .categories()
                        .map {
                            FaceLandmarkResult.Blendshape(
                                name = it.categoryName(),
                                score = it.score()
                            )
                        }

                } else {
                    emptyList()
                }

            output += FaceLandmarkResult(
                boundingBox = boundingBox,
                landmarks = points,
                blendshapes = blendshapes
            )
        }

        return output
    }

    private fun calculateBoundingBox(
        points: List<FaceLandmarkResult.Point>,
        width: Int,
        height: Int
    ): RectF {

        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f

        for (point in points) {

            minX = min(minX, point.x)
            minY = min(minY, point.y)

            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }

        return RectF(
            minX.coerceIn(0f, 1f),
            minY.coerceIn(0f, 1f),
            maxX.coerceIn(0f, 1f),
            maxY.coerceIn(0f, 1f)
        )
    }

    override fun close() {
        landmarker.close()
    }

    companion object {

        private const val MODEL_FILE =
            "face_landmarker.task"

        private const val MAX_FACES = 20

        private const val MIN_DETECTION_CONFIDENCE = 0.5f

        private const val MIN_PRESENCE_CONFIDENCE = 0.5f

        private const val MIN_TRACKING_CONFIDENCE = 0.5f
    }
}
