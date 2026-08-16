package com.example.personalmemoryai.vision

import android.graphics.Bitmap

class FaceAnalysisService(
    private val faceAnalyzer: MediaPipeFaceAnalyzer,
    private val embeddingModel: FaceEmbeddingModel,
    private val qualityAnalyzer: FaceQualityAnalyzer =
        FaceQualityAnalyzer()
) {

    data class AnalyzedFace(
        val detection: FaceLandmarkResult,
        val quality: FaceQualityAnalyzer.QualityResult,
        val embedding: FloatArray?,
        val embeddingModelName: String?,
        val embeddingModelVersion: String?,
        val usableForMatching: Boolean
    )

    suspend fun analyze(
        bitmap: Bitmap
    ): List<AnalyzedFace> {

        val detections =
            faceAnalyzer.analyze(bitmap)

        if (detections.isEmpty()) {
            return emptyList()
        }

        val results =
            ArrayList<AnalyzedFace>(
                detections.size
            )

        for (detection in detections) {

            val crop =
                FaceCropper.crop(
                    source = bitmap,
                    normalizedBox =
                        detection.boundingBox
                )

            if (crop == null) {

                results +=
                    AnalyzedFace(
                        detection = detection,
                        quality =
                            FaceQualityAnalyzer.QualityResult(
                                score = 0f,
                                sharpness = 0f,
                                brightness = 0f,
                                contrast = 0f,
                                usable = false
                            ),
                        embedding = null,
                        embeddingModelName = null,
                        embeddingModelVersion = null,
                        usableForMatching = false
                    )

                continue
            }

            val quality =
                qualityAnalyzer.analyze(
                    crop
                )

            if (!quality.usable) {

                results +=
                    AnalyzedFace(
                        detection = detection,
                        quality = quality,
                        embedding = null,
                        embeddingModelName = null,
                        embeddingModelVersion = null,
                        usableForMatching = false
                    )

                crop.recycleIfNeeded()

                continue
            }

            val embeddingResult =
                try {

                    embeddingModel
                        .generateEmbedding(crop)

                } catch (_: Throwable) {

                    null
                }

            val normalizedEmbedding =
                embeddingResult
                    ?.let {
                        FaceSimilarity.normalize(it)
                    }

            val validEmbedding =
                normalizedEmbedding != null &&
                    normalizedEmbedding.isNotEmpty()

            results +=
                AnalyzedFace(
                    detection = detection,
                    quality = quality,
                    embedding =
                        if (validEmbedding) {
                            normalizedEmbedding
                        } else {
                            null
                        },
                    embeddingModelName =
                        if (validEmbedding) {
                            embeddingModel.modelName
                        } else {
                            null
                        },
                    embeddingModelVersion =
                        if (validEmbedding) {
                            embeddingModel.modelVersion
                        } else {
                            null
                        },
                    usableForMatching =
                        validEmbedding
                )

            crop.recycleIfNeeded()
        }

        return results
    }

    private fun Bitmap.recycleIfNeeded() {
        if (!isRecycled) {
            recycle()
        }
    }

    fun close() {
        faceAnalyzer.close()
        embeddingModel.close()
    }
}
