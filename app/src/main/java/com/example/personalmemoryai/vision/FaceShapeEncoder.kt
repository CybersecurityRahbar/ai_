package com.example.personalmemoryai.vision

import kotlin.math.sqrt

/**
 * Converts MediaPipe landmarks into a translation/scale-normalized facial-shape vector.
 * This is a complementary signal to the learned face embedding; it must never be treated
 * as a verified identity by itself.
 */
object FaceShapeEncoder {
    const val MODEL_NAME = "MediaPipe-FaceShape"
    const val MODEL_VERSION = "478-landmarks-v1"
    const val OWNER_TYPE = "FACE_LANDMARKS"

    fun encode(result: FaceLandmarkResult): FloatArray {
        val box = result.boundingBox
        val width = (box.right - box.left).coerceAtLeast(1e-6f)
        val height = (box.bottom - box.top).coerceAtLeast(1e-6f)
        if (result.landmarks.isEmpty()) return FloatArray(0)

        val vector = FloatArray(result.landmarks.size * 3)
        var index = 0
        for (point in result.landmarks) {
            vector[index++] = ((point.x - box.left) / width).coerceIn(-1f, 2f)
            vector[index++] = ((point.y - box.top) / height).coerceIn(-1f, 2f)
            vector[index++] = point.z.coerceIn(-2f, 2f)
        }
        return normalize(vector)
    }

    fun similarity(first: FloatArray, second: FloatArray): Float {
        if (first.isEmpty() || second.isEmpty() || first.size != second.size) return 0f
        var sum = 0.0
        var count = 0
        for (i in first.indices) {
            val d = first[i].toDouble() - second[i].toDouble()
            sum += d * d
            count++
        }
        if (count == 0) return 0f
        val distance = sqrt(sum / count).toFloat()
        return (1f - distance / 0.75f).coerceIn(0f, 1f)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var norm = 0.0
        for (v in vector) norm += v.toDouble() * v.toDouble()
        val n = sqrt(norm)
        if (n <= 1e-12) return vector
        return FloatArray(vector.size) { (vector[it].toDouble() / n).toFloat() }
    }
}
