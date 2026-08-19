package com.example.personalmemoryai.vision

import android.graphics.Bitmap

/** Runs detection, landmarks, pose, quality analysis and identity embedding without allowing one bad face to abort the image. */
class FaceAnalysisService(
    private val faceAnalyzer: MediaPipeFaceAnalyzer,
    private val embeddingModel: FaceEmbeddingModel,
    private val qualityAnalyzer: FaceQualityAnalyzer = FaceQualityAnalyzer()
) {
    data class AnalyzedFace(
        val detection: FaceLandmarkResult,
        val quality: FaceQualityAnalyzer.QualityResult,
        val embedding: FloatArray?,
        val landmarkShape: FloatArray?,
        val pose: FacePoseEstimator.Pose?,
        val embeddingModelName: String?,
        val embeddingModelVersion: String?,
        val embeddingError: String? = null,
        val usableForMatching: Boolean
    )

    suspend fun analyze(bitmap: Bitmap): List<AnalyzedFace> {
        val detections = faceAnalyzer.analyze(bitmap)
        if (detections.isEmpty()) return emptyList()
        val results = ArrayList<AnalyzedFace>(detections.size)
        for (detection in detections) {
            val crop = FaceCropper.crop(bitmap, detection.boundingBox)
            if (crop == null) {
                results += emptyResult(detection, null, "FACE_CROP_FAILED")
                continue
            }
            try {
                val quality = qualityAnalyzer.analyze(crop)
                val shape = FaceShapeEncoder.encode(detection)
                val pose = FacePoseEstimator.estimate(detection)
                var embeddingError: String? = null
                val embeddingResult = try {
                    embeddingModel.generateEmbedding(crop)
                } catch (t: Throwable) {
                    embeddingError = t.message ?: t.javaClass.simpleName
                    FloatArray(0)
                }
                val normalized = if (embeddingResult.isNotEmpty()) FaceSimilarity.normalize(embeddingResult) else FloatArray(0)
                val validEmbedding = normalized.isNotEmpty() && normalized.all { it.isFinite() }
                val validShape = shape.isNotEmpty() && shape.all { it.isFinite() }
                val enrichedDetection = detection.copy(
                    rotationX = pose?.pitch,
                    rotationY = pose?.yaw,
                    rotationZ = pose?.roll
                )
                results += AnalyzedFace(
                    detection = enrichedDetection,
                    quality = quality,
                    embedding = if (validEmbedding) normalized else null,
                    landmarkShape = if (validShape) shape else null,
                    pose = pose,
                    embeddingModelName = if (validEmbedding) embeddingModel.modelName else null,
                    embeddingModelVersion = if (validEmbedding) embeddingModel.modelVersion else null,
                    embeddingError = embeddingError,
                    // Quality is a scoring signal, not a hard gate. This prevents usable faces
                    // from disappearing from the index merely because of blur/lighting/size.
                    usableForMatching = validEmbedding && validShape && pose != null
                )
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }
        return results
    }

    private fun emptyResult(
        detection: FaceLandmarkResult,
        quality: FaceQualityAnalyzer.QualityResult?,
        error: String?
    ) = AnalyzedFace(
        detection = detection,
        quality = quality ?: FaceQualityAnalyzer.QualityResult(0f, 0f, 0f, 0f, false),
        embedding = null,
        landmarkShape = FaceShapeEncoder.encode(detection).takeIf { it.isNotEmpty() },
        pose = FacePoseEstimator.estimate(detection),
        embeddingModelName = null,
        embeddingModelVersion = null,
        embeddingError = error,
        usableForMatching = false
    )

    fun close() {
        faceAnalyzer.close()
        embeddingModel.close()
    }
}
