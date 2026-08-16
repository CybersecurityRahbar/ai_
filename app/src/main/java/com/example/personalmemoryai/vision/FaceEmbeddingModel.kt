package com.example.personalmemoryai.vision

import android.graphics.Bitmap

/**
 * Abstraction for a face embedding model.
 *
 * The rest of the application never needs to know which
 * neural-network implementation is being used.
 *
 * A future TensorFlow Lite / MediaPipe / custom model can
 * implement this interface without changing the database,
 * search engine, or UI.
 */
interface FaceEmbeddingModel {

    /**
     * Name of the model.
     */
    val modelName: String

    /**
     * Exact model version.
     */
    val modelVersion: String

    /**
     * Output vector dimension.
     */
    val embeddingDimension: Int

    /**
     * Generates an embedding for a face crop.
     *
     * The bitmap should contain a single face whenever possible.
     *
     * @return normalized embedding vector.
     */
    suspend fun generateEmbedding(
        faceBitmap: Bitmap
    ): FloatArray

    /**
     * Releases model resources.
     */
    fun close()
}
