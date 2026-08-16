package com.example.personalmemoryai.vision

import kotlin.math.sqrt

/**
 * Mathematical similarity functions for normalized embeddings.
 */
object FaceSimilarity {

    /**
     * Cosine similarity.
     *
     * For normalized vectors:
     *
     * 1.0  -> extremely similar
     * 0.0  -> weak/no directional similarity
     * -1.0 -> opposite direction
     */
    fun cosineSimilarity(
        first: FloatArray,
        second: FloatArray
    ): Float {

        require(first.size == second.size) {
            "Embedding dimensions do not match: " +
                "${first.size} != ${second.size}"
        }

        if (first.isEmpty()) {
            return 0f
        }

        var dot = 0.0
        var firstNorm = 0.0
        var secondNorm = 0.0

        for (index in first.indices) {

            val a = first[index].toDouble()
            val b = second[index].toDouble()

            dot += a * b

            firstNorm += a * a
            secondNorm += b * b
        }

        if (firstNorm <= 0.0 || secondNorm <= 0.0) {
            return 0f
        }

        val similarity =
            dot /
                (
                    sqrt(firstNorm) *
                    sqrt(secondNorm)
                )

        return similarity
            .coerceIn(-1.0, 1.0)
            .toFloat()
    }

    /**
     * Converts cosine similarity into a convenient 0..1 scale.
     */
    fun normalizedSimilarity(
        first: FloatArray,
        second: FloatArray
    ): Float {

        val cosine =
            cosineSimilarity(
                first,
                second
            )

        return (
            (cosine + 1f) / 2f
        ).coerceIn(0f, 1f)
    }

    /**
     * Euclidean distance.
     *
     * Smaller distance means greater similarity.
     */
    fun euclideanDistance(
        first: FloatArray,
        second: FloatArray
    ): Float {

        require(first.size == second.size) {
            "Embedding dimensions do not match"
        }

        var sum = 0.0

        for (index in first.indices) {

            val difference =
                first[index] -
                    second[index]

            sum +=
                difference.toDouble() *
                difference.toDouble()
        }

        return sqrt(sum).toFloat()
    }

    /**
     * L2 normalization.
     */
    fun normalize(
        vector: FloatArray
    ): FloatArray {

        if (vector.isEmpty()) {
            return vector
        }

        var sum = 0.0

        for (value in vector) {
            sum +=
                value.toDouble() *
                value.toDouble()
        }

        val norm = sqrt(sum)

        if (norm <= 0.0) {
            return vector.copyOf()
        }

        return FloatArray(vector.size) { index ->
            (
                vector[index].toDouble() /
                    norm
            ).toFloat()
        }
    }
}
