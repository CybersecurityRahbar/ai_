package com.example.personalmemoryai.vision

import android.graphics.Bitmap

/**
 * Runs face detection/landmarks/pose/quality and one or more identity models.
 * Every model is isolated: one failed model never prevents another model from
 * producing an embedding for the same face.
 */
class FaceAnalysisService(
    private val faceAnalyzer: MediaPipeFaceAnalyzer,
    private val embeddingModel: FaceEmbeddingModel,
    private val additionalModels: List<FaceEmbeddingModel> = emptyList(),
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
        val embeddings: Map<String, ModelEmbedding>,
        val embeddingErrors: Map<String, String>,
        val embeddingError: String? = null,
        val usableForMatching: Boolean
    )

    data class ModelEmbedding(
        val modelName: String,
        val modelVersion: String,
        val vector: FloatArray
    )

    private val models: List<FaceEmbeddingModel>
        get() = listOf(embeddingModel) + additionalModels.filterNot { it === embeddingModel }

    suspend fun analyze(bitmap: Bitmap): List<AnalyzedFace> {
        val detections = faceAnalyzer.analyze(bitmap)
        if (detections.isEmpty()) return emptyList()
        val results = ArrayList<AnalyzedFace>(detections.size)
        for (detection in detections) {
            val crop = FaceCropper.crop(bitmap, detection.boundingBox)
            if (crop == null) {
                results += emptyResult(detection, "FACE_CROP_FAILED")
                continue
            }
            try {
                val quality = qualityAnalyzer.analyze(crop)
                val shape = FaceShapeEncoder.encode(detection)
                val pose = FacePoseEstimator.estimate(detection)
                val validEmbeddings = linkedMapOf<String, ModelEmbedding>()
                val errors = linkedMapOf<String, String>()

                for (model in models) {
                    try {
                        val raw = model.generateEmbedding(crop)
                        val normalized = if (raw.isNotEmpty()) FaceSimilarity.normalize(raw) else FloatArray(0)
                        if (normalized.isNotEmpty() && normalized.all { it.isFinite() }) {
                            validEmbeddings[model.modelName.lowercase()] = ModelEmbedding(
                                model.modelName,
                                model.modelVersion,
                                normalized
                            )
                        } else {
                            errors[model.modelName] = "EMPTY_OR_NON_FINITE_EMBEDDING"
                        }
                    } catch (t: Throwable) {
                        errors[model.modelName] = t.message ?: t.javaClass.simpleName
                    }
                }

                val primary = validEmbeddings[embeddingModel.modelName.lowercase()]
                val enrichedDetection = detection.copy(
                    rotationX = pose?.pitch,
                    rotationY = pose?.yaw,
                    rotationZ = pose?.roll
                )
                val validShape = shape.isNotEmpty() && shape.all { it.isFinite() }
                results += AnalyzedFace(
                    detection = enrichedDetection,
                    quality = quality,
                    embedding = primary?.vector,
                    landmarkShape = if (validShape) shape else null,
                    pose = pose,
                    embeddingModelName = primary?.modelName,
                    embeddingModelVersion = primary?.modelVersion,
                    embeddings = validEmbeddings,
                    embeddingErrors = errors,
                    embeddingError = errors[embeddingModel.modelName],
                    usableForMatching = validEmbeddings.isNotEmpty() && validShape && pose != null
                )
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }
        return results
    }

    private fun emptyResult(detection: FaceLandmarkResult, error: String) = AnalyzedFace(
        detection = detection,
        quality = FaceQualityAnalyzer.QualityResult(0f, 0f, 0f, 0f, false),
        embedding = null,
        landmarkShape = FaceShapeEncoder.encode(detection).takeIf { it.isNotEmpty() },
        pose = FacePoseEstimator.estimate(detection),
        embeddingModelName = null,
        embeddingModelVersion = null,
        embeddings = emptyMap(),
        embeddingErrors = mapOf("pipeline" to error),
        embeddingError = error,
        usableForMatching = false
    )

    fun close() {
        try { faceAnalyzer.close() } catch (_: Throwable) { }
        models.distinctBy { it.modelName.lowercase() }.forEach {
            try { it.close() } catch (_: Throwable) { }
        }
    }
}
