package com.example.personalmemoryai.vision

import android.graphics.Bitmap

/**
 * Runs face detection/landmarks, quality analysis and identity embedding.
 * Quality is metadata only: it must never silently prevent an otherwise
 * detected face from receiving an identity embedding.
 */
class FaceAnalysisService(
    private val faceAnalyzer: MediaPipeFaceAnalyzer,
    private val embeddingModel: FaceEmbeddingModel,
    private val qualityAnalyzer: FaceQualityAnalyzer = FaceQualityAnalyzer()
) {
    data class AnalyzedFace(
        val detection: FaceLandmarkResult,
        val quality: FaceQualityAnalyzer.QualityResult,
        val embedding: FloatArray?,
        val embeddingModelName: String?,
        val embeddingModelVersion: String?,
        val usableForMatching: Boolean
    )

    suspend fun analyze(bitmap: Bitmap): List<AnalyzedFace> {
        val detections = faceAnalyzer.analyze(bitmap)
        if (detections.isEmpty()) return emptyList()

        val results = ArrayList<AnalyzedFace>(detections.size)
        for (detection in detections) {
            val crop = FaceCropper.crop(bitmap, detection.boundingBox)
            if (crop == null) {
                results += emptyResult(detection, null)
                continue
            }

            try {
                val quality = qualityAnalyzer.analyze(crop)

                // Do not use quality.usable as an embedding gate. A face that is
                // dim, compressed, partially occluded or low contrast is still
                // useful for investigation and must be represented in the index.
                val embeddingResult = embeddingModel.generateEmbedding(crop)
                val normalized = FaceSimilarity.normalize(embeddingResult)
                val valid = normalized.isNotEmpty() && normalized.all { it.isFinite() }

                results += AnalyzedFace(
                    detection = detection,
                    quality = quality,
                    embedding = if (valid) normalized else null,
                    embeddingModelName = if (valid) embeddingModel.modelName else null,
                    embeddingModelVersion = if (valid) embeddingModel.modelVersion else null,
                    usableForMatching = valid
                )

                if (!valid) {
                    throw IllegalStateException("Face embedding model returned an empty or non-finite vector")
                }
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }
        return results
    }

    private fun emptyResult(
        detection: FaceLandmarkResult,
        quality: FaceQualityAnalyzer.QualityResult?
    ) = AnalyzedFace(
        detection = detection,
        quality = quality ?: FaceQualityAnalyzer.QualityResult(0f, 0f, 0f, 0f, false),
        embedding = null,
        embeddingModelName = null,
        embeddingModelVersion = null,
        usableForMatching = false
    )

    fun close() {
        faceAnalyzer.close()
        embeddingModel.close()
    }
}
