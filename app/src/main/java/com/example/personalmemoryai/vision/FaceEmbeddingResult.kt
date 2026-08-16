package com.example.personalmemoryai.vision

/**
 * Result returned after attempting to generate a face embedding.
 */
data class FaceEmbeddingResult(

    val success: Boolean,

    val embedding: FloatArray? = null,

    val modelName: String? = null,

    val modelVersion: String? = null,

    val dimension: Int = 0,

    val qualityScore: Float = 0f,

    val reason: FailureReason? = null
) {

    enum class FailureReason {

        NO_FACE,

        FACE_TOO_SMALL,

        FACE_TOO_BLURRY,

        FACE_TOO_DARK,

        FACE_TOO_BRIGHT,

        FACE_OCCLUDED,

        INVALID_BITMAP,

        MODEL_ERROR,

        INVALID_EMBEDDING
    }

    companion object {

        fun failure(
            reason: FailureReason,
            qualityScore: Float = 0f
        ): FaceEmbeddingResult {

            return FaceEmbeddingResult(
                success = false,
                qualityScore = qualityScore,
                reason = reason
            )
        }

        fun success(
            embedding: FloatArray,
            modelName: String,
            modelVersion: String,
            qualityScore: Float
        ): FaceEmbeddingResult {

            return FaceEmbeddingResult(
                success = true,
                embedding = embedding,
                modelName = modelName,
                modelVersion = modelVersion,
                dimension = embedding.size,
                qualityScore = qualityScore
            )
        }
    }
}
