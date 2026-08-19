package com.example.personalmemoryai.intelligence

import kotlin.math.sqrt

/**
 * Deterministic, offline evidence-fusion layer.
 *
 * This class deliberately does not claim real-world identity. It ranks
 * already-detected face observations using multiple measurable signals:
 * embedding similarity, detection confidence, crop quality and head pose.
 * Missing signals are ignored and the final score is renormalized.
 */
object MultiSignalMatchEngine {

    data class Evidence(
        val embeddingSimilarity: Float? = null,
        val detectionConfidence: Float? = null,
        val qualityScore: Float? = null,
        val rotationX: Float? = null,
        val rotationY: Float? = null,
        val rotationZ: Float? = null,
        val referenceRotationX: Float? = null,
        val referenceRotationY: Float? = null,
        val referenceRotationZ: Float? = null
    )

    data class Score(
        val overall: Float,
        val embedding: Float?,
        val quality: Float?,
        val pose: Float?,
        val detection: Float?,
        val confidenceBand: Band
    )

    enum class Band { HIGH, MEDIUM, LOW, INSUFFICIENT }

    fun score(e: Evidence): Score {
        val parts = ArrayList<Pair<Float, Float>>(4)

        e.embeddingSimilarity?.let { parts += clamp01(it) to 0.60f }
        e.detectionConfidence?.let { parts += clamp01(it) to 0.10f }
        e.qualityScore?.let { parts += clamp01(it) to 0.10f }

        val pose = poseSimilarity(e)
        pose?.let { parts += it to 0.20f }

        if (parts.isEmpty()) return Score(0f, e.embeddingSimilarity, e.qualityScore, pose, e.detectionConfidence, Band.INSUFFICIENT)

        val weight = parts.sumOf { it.second.toDouble() }.toFloat()
        val overall = parts.sumOf { (it.first * it.second).toDouble() }.toFloat() / weight
        val band = when {
            overall >= 0.85f -> Band.HIGH
            overall >= 0.65f -> Band.MEDIUM
            else -> Band.LOW
        }
        return Score(overall, e.embeddingSimilarity, e.qualityScore, pose, e.detectionConfidence, band)
    }

    /** Cosine similarity, tolerant of zero/invalid vectors. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na <= 1e-12 || nb <= 1e-12) return 0f
        return clamp01(((dot / (sqrt(na) * sqrt(nb)) + 1.0) / 2.0).toFloat())
    }

    private fun poseSimilarity(e: Evidence): Float? {
        val rx = e.rotationX ?: return null
        val ry = e.rotationY ?: return null
        val rz = e.rotationZ ?: return null
        val rrx = e.referenceRotationX ?: return null
        val rry = e.referenceRotationY ?: return null
        val rrz = e.referenceRotationZ ?: return null
        val distance = (kotlin.math.abs(rx - rrx) + kotlin.math.abs(ry - rry) + kotlin.math.abs(rz - rrz)) / 3f
        return 1f - clamp01(distance / 45f)
    }

    private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)
}
