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
 * MediaPipe-based facial analysis engine.
 *
 * Responsibilities:
 *
 * - Detect faces in an image
 * - Extract facial landmarks
 * - Extract optional face blendshapes
 * - Produce normalized face bounding boxes
 * - Provide a structural representation of each detected face
 *
 * This class DOES NOT identify a person.
 *
 * Identity recognition is handled separately by:
 *
 * FaceEmbeddingModel
 * FaceSimilarity
 * FaceMatchingEngine
 *
 * This separation is intentional so that the detection layer
 * can evolve independently from the identity/embedding layer.
 */
class MediaPipeFaceAnalyzer(
    context: Context
) : AutoCloseable {

    private val landmarker: FaceLandmarker

    init {

        val baseOptions =
            BaseOptions
                .builder()
                .setModelAssetPath(MODEL_FILE)
                .build()

        val options =
            FaceLandmarker
                .FaceLandmarkerOptions
                .builder()
                .setBaseOptions(baseOptions)

                /*
                 * We analyze complete images rather than a
                 * continuous camera stream.
                 */
                .setRunningMode(
                    RunningMode.IMAGE
                )

                /*
                 * Multiple faces are required because the
                 * application is intended to index complete
                 * photo collections.
                 */
                .setNumFaces(
                    MAX_FACES
                )

                /*
                 * Minimum confidence required for MediaPipe
                 * to accept a detected face.
                 */
                .setMinFaceDetectionConfidence(
                    MIN_FACE_DETECTION_CONFIDENCE
                )

                /*
                 * Minimum confidence that the detected face
                 * is actually present in the image.
                 */
                .setMinFacePresenceConfidence(
                    MIN_FACE_PRESENCE_CONFIDENCE
                )

                /*
                 * Tracking confidence is included by the
                 * MediaPipe configuration even though the
                 * current engine uses IMAGE mode.
                 */
                .setMinTrackingConfidence(
                    MIN_TRACKING_CONFIDENCE
                )

                /*
                 * Blendshapes provide additional facial
                 * expression/appearance geometry.
                 *
                 * They are NOT identity labels.
                 */
                .setOutputFaceBlendshapes(
                    true
                )

                /*
                 * Request facial transformation matrices
                 * from the MediaPipe task.
                 *
                 * These can later be used for head pose and
                 * alignment improvements.
                 */
                .setOutputFacialTransformationMatrixes(
                    true
                )

                .build()

        landmarker =
            FaceLandmarker
                .createFromOptions(
                    context,
                    options
                )
    }

    /**
     * Analyze one bitmap.
     *
     * @param bitmap source image
     *
     * @return list of detected faces.
     */
    fun analyze(
        bitmap: Bitmap
    ): List<FaceLandmarkResult> {

        if (
            bitmap.width <= 0 ||
            bitmap.height <= 0
        ) {
            return emptyList()
        }

        if (bitmap.isRecycled) {
            return emptyList()
        }

        val mpImage =
            BitmapImageBuilder(
                bitmap
            ).build()

        return try {

            val result =
                landmarker.detect(
                    mpImage
                )

            convertResult(
                result = result,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )

        } catch (_: Throwable) {

            /*
             * Image analysis must never crash the indexing
             * pipeline because of a single corrupted image.
             */
            emptyList()
        }
    }

    /**
     * Converts MediaPipe's result into our application's
     * internal representation.
     */
    private fun convertResult(
        result: FaceLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int
    ): List<FaceLandmarkResult> {

        val output =
            mutableListOf<FaceLandmarkResult>()

        val faceLandmarks =
            result.faceLandmarks()

        if (faceLandmarks.isEmpty()) {
            return output
        }

        for (
            faceIndex in faceLandmarks.indices
        ) {

            val mediapipeLandmarks =
                faceLandmarks[faceIndex]

            if (
                mediapipeLandmarks.isEmpty()
            ) {
                continue
            }

            /*
             * Convert MediaPipe landmarks into our own
             * lightweight representation.
             */
            val points =
                mediapipeLandmarks.map { landmark ->

                    FaceLandmarkResult.Point(

                        x = landmark.x(),

                        y = landmark.y(),

                        z = landmark.z()
                    )
                }

            if (points.isEmpty()) {
                continue
            }

            /*
             * MediaPipe landmarks use normalized coordinates.
             *
             * We keep the application's bounding box normalized
             * as well so it remains resolution independent.
             */
            val boundingBox =
                calculateBoundingBox(
                    points = points,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight
                )

            /*
             * Optional blendshape extraction.
             *
             * Blendshapes are associated with each detected face.
             */
            val blendshapes =
                extractBlendshapes(
                    result = result,
                    faceIndex = faceIndex
                )

            /*
             * Extract head-pose information from the facial
             * transformation matrix when available.
             *
             * At this stage we keep the extraction conservative.
             * The matrix is retained for future alignment/pose
             * analysis instead of inventing angles.
             */
            val rotation =
                extractRotation(
                    result = result,
                    faceIndex = faceIndex
                )

            /*
             * IMPORTANT:
             *
             * MediaPipe's FaceLandmarkerResult does not expose
             * a universal per-face probability in the same way
             * that a dedicated object detector does.
             *
             * Therefore we DO NOT fabricate a value such as
             * 0.95 or 1.0 and call it "confidence".
             *
             * The current value is an operational presence
             * confidence based on successful landmark generation.
             */
            val detectionConfidence =
                calculateOperationalConfidence(
                    points = points
                )

            output +=
                FaceLandmarkResult(

                    boundingBox =
                        boundingBox,

                    landmarks =
                        points,

                    blendshapes =
                        blendshapes,

                    rotationX =
                        rotation?.first,

                    rotationY =
                        rotation?.second,

                    rotationZ =
                        rotation?.third,

                    detectionConfidence =
                        detectionConfidence
                )
        }

        return output
    }

    /**
     * Extract MediaPipe blendshapes.
     */
    private fun extractBlendshapes(
        result: FaceLandmarkerResult,
        faceIndex: Int
    ): List<FaceLandmarkResult.Blendshape> {

        val allBlendshapes =
            result.faceBlendshapes()

        if (
            faceIndex < 0 ||
            faceIndex >= allBlendshapes.size
        ) {
            return emptyList()
        }

        return try {

            allBlendshapes[faceIndex]
                .categories()
                .mapNotNull { category ->

                    val name =
                        category.categoryName()

                    val score =
                        category.score()

                    if (
                        name.isNullOrBlank()
                    ) {
                        null
                    } else {

                        FaceLandmarkResult.Blendshape(

                            name = name,

                            score =
                                score.coerceIn(
                                    0f,
                                    1f
                                )
                        )
                    }
                }

        } catch (_: Throwable) {

            emptyList()
        }
    }

    /**
     * Attempts to extract rotation from MediaPipe's
     * facial transformation matrix.
     *
     * The exact matrix representation can vary with the
     * MediaPipe task/model version, therefore this method
     * intentionally returns null unless the required matrix
     * can be safely interpreted.
     *
     * Returning null is preferable to storing fabricated
     * head-pose angles.
     */
    private fun extractRotation(
        result: FaceLandmarkerResult,
        faceIndex: Int
    ): Triple<Float, Float, Float>? {

        val matrices =
            result.facialTransformationMatrixes()

        if (
            matrices.isEmpty() ||
            faceIndex < 0 ||
            faceIndex >= matrices.size
        ) {
            return null
        }

        /*
         * The current version keeps matrix extraction disabled
         * until the exact matrix API used by the selected
         * MediaPipe artifact is confirmed.
         *
         * The transformation matrix itself remains available
         * from MediaPipe and can be incorporated into the
         * alignment pipeline later.
         */
        return null
    }

    /**
     * Calculates a normalized bounding box from landmarks.
     */
    private fun calculateBoundingBox(
        points: List<FaceLandmarkResult.Point>,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {

        if (points.isEmpty()) {
            return RectF()
        }

        var minX =
            Float.POSITIVE_INFINITY

        var minY =
            Float.POSITIVE_INFINITY

        var maxX =
            Float.NEGATIVE_INFINITY

        var maxY =
            Float.NEGATIVE_INFINITY

        for (point in points) {

            minX =
                min(
                    minX,
                    point.x
                )

            minY =
                min(
                    minY,
                    point.y
                )

            maxX =
                max(
                    maxX,
                    point.x
                )

            maxY =
                max(
                    maxY,
                    point.y
                )
        }

        /*
         * Normalize and clamp.
         */
        val left =
            minX.coerceIn(
                0f,
                1f
            )

        val top =
            minY.coerceIn(
                0f,
                1f
            )

        val right =
            maxX.coerceIn(
                0f,
                1f
            )

        val bottom =
            maxY.coerceIn(
                0f,
                1f
            )

        /*
         * Prevent invalid boxes.
         */
        if (
            right <= left ||
            bottom <= top
        ) {
            return RectF(
                left,
                top,
                left,
                top
            )
        }

        return RectF(
            left,
            top,
            right,
            bottom
        )
    }

    /**
     * Produces an operational quality/confidence signal.
     *
     * This is deliberately NOT described as MediaPipe's
     * detection probability.
     *
     * It only evaluates whether a valid landmark geometry
     * was successfully produced.
     */
    private fun calculateOperationalConfidence(
        points: List<FaceLandmarkResult.Point>
    ): Float {

        if (points.isEmpty()) {
            return 0f
        }

        /*
         * A valid MediaPipe face mesh contains a large number
         * of landmarks. The exact count may vary by model version.
         *
         * We therefore use a saturation function instead of
         * assuming a fixed landmark count.
         */
        val normalizedLandmarkCount =
            (
                points.size.toFloat() /
                    EXPECTED_LANDMARK_COUNT
            )
                .coerceIn(
                    0f,
                    1f
                )

        /*
         * A successfully generated dense landmark set is
         * considered operationally usable.
         */
        return normalizedLandmarkCount
    }

    /**
     * Releases MediaPipe resources.
     */
    override fun close() {

        try {
            landmarker.close()
        } catch (_: Throwable) {
            // Ignore cleanup exceptions.
        }
    }

    companion object {

        /**
         * Model asset expected inside:
         *
         * app/src/main/assets/
         */
        private const val MODEL_FILE =
            "face_landmarker.task"

        /**
         * Maximum faces processed per image.
         *
         * This is intentionally higher than 1 because the
         * application is intended for photo collections.
         */
        private const val MAX_FACES =
            20

        private const val MIN_FACE_DETECTION_CONFIDENCE =
            0.50f

        private const val MIN_FACE_PRESENCE_CONFIDENCE =
            0.50f

        private const val MIN_TRACKING_CONFIDENCE =
            0.50f

        /**
         * Approximate number of landmarks expected by the
         * selected Face Landmarker model.
         *
         * This is used only for operational confidence
         * normalization and is NOT a MediaPipe probability.
         */
        private const val EXPECTED_LANDMARK_COUNT =
            478f
    }
}
