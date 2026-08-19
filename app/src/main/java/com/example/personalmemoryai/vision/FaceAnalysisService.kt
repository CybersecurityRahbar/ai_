package com.example.personalmemoryai.vision

import android.graphics.Bitmap

/** Runs detection, landmarks, quality analysis and identity embedding without allowing one bad face to abort the image. */
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
                val embeddingResult = try { embeddingModel.generateEmbedding(crop) } catch (_: Throwable) { FloatArray(0) }
                val normalized = if (embeddingResult.isNotEmpty()) FaceSimilarity.normalize(embeddingResult) else FloatArray(0)
                val valid = normalized.isNotEmpty() && normalized.all { it.isFinite() }
                results += AnalyzedFace(
                    detection = detection,
                    quality = quality,
                    embedding = if (valid) normalized else null,
                    embeddingModelName = if (valid) embeddingModel.modelName else null,
                    embeddingModelVersion = if (valid) embeddingModel.modelVersion else null,
                    usableForMatching = valid
                )
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }
        return results
    }

    private fun emptyResult(detection: FaceLandmarkResult, quality: FaceQualityAnalyzer.QualityResult?) = AnalyzedFace(
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
