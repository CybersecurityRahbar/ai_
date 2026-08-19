package com.example.personalmemoryai.vision

import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceEntity
import kotlin.math.abs

/**
 * Conservative evidence combiner used by person clustering.
 *
 * It never declares a legal/real-world identity. It produces a visual
 * association score from independent signals and can abstain when evidence
 * is weak, contradictory, or based on a poor-quality observation.
 */
object IdentityEvidenceEngine {
    data class Template(
        val face: FaceEntity,
        val identity: EmbeddingEntity?,
        val shape: EmbeddingEntity?
    )

    data class Evidence(
        val composite: Float,
        val identity: Float,
        val shape: Float,
        val pose: Float,
        val quality: Float,
        val corroboration: Float,
        val templateCount: Int
    )

    fun compare(query: FaceEntity, queryIdentity: EmbeddingEntity?, queryShape: EmbeddingEntity?, templates: List<Template>): Evidence? {
        if (templates.isEmpty()) return null

        val scored = templates.mapNotNull { template ->
            val identity = if (queryIdentity != null && template.identity != null &&
                queryIdentity.dimension == template.identity.dimension &&
                queryIdentity.vector.isNotEmpty() && template.identity.vector.isNotEmpty()
            ) FaceSimilarity.cosineSimilarity(queryIdentity.vector, template.identity.vector).coerceIn(0f, 1f) else null

            val shape = if (queryShape != null && template.shape != null &&
                queryShape.dimension == template.shape.dimension &&
                queryShape.vector.isNotEmpty() && template.shape.vector.isNotEmpty()
            ) FaceShapeEncoder.similarity(queryShape.vector, template.shape.vector) else null

            val pose = poseCompatibility(query, template.face)
            val quality = ((query.qualityScore.coerceIn(0f, 1f) + template.face.qualityScore.coerceIn(0f, 1f)) * 0.5f)

            if (identity == null && shape == null) null
            else {
                val visual = when {
                    identity != null && shape != null -> 0.82f * identity + 0.18f * shape
                    identity != null -> identity
                    else -> shape!! * 0.92f
                }
                val adjusted = (visual * (0.88f + 0.12f * quality) * (0.92f + 0.08f * pose)).coerceIn(0f, 1f)
                Triple(adjusted, identity ?: 0f, shape ?: 0f) to Pair(pose, quality)
            }
        }.sortedByDescending { it.first.first }

        if (scored.isEmpty()) return null

        val top = scored[0]
        val second = scored.getOrNull(1)
        val corroboration = if (second != null) {
            val closeness = (1f - (top.first.first - second.first.first).coerceAtLeast(0f) / 0.25f).coerceIn(0f, 1f)
            0.65f * second.first.first + 0.35f * closeness
        } else 0f

        val finalScore = if (second != null) {
            (0.84f * top.first.first + 0.16f * corroboration).coerceIn(0f, 1f)
        } else top.first.first

        return Evidence(
            composite = finalScore,
            identity = top.first.second,
            shape = top.first.third,
            pose = top.second.first,
            quality = top.second.second,
            corroboration = corroboration,
            templateCount = scored.size
        )
    }

    fun shouldAssociate(evidence: Evidence): Boolean {
        if (evidence.identity >= 0.90f && evidence.quality >= 0.35f) return true
        if (evidence.identity >= 0.82f && evidence.shape >= 0.86f && evidence.corroboration >= 0.70f) return true
        if (evidence.identity >= 0.76f && evidence.shape >= 0.90f && evidence.pose >= 0.55f && evidence.quality >= 0.45f) return true
        if (evidence.identity >= 0.84f && evidence.composite >= 0.82f) return true
        return evidence.composite >= 0.89f && evidence.quality >= 0.55f && evidence.corroboration >= 0.70f
    }

    private fun poseCompatibility(a: FaceEntity, b: FaceEntity): Float {
        val ax = a.rotationX
        val ay = a.rotationY
        val az = a.rotationZ
        val bx = b.rotationX
        val by = b.rotationY
        val bz = b.rotationZ
        if (ax == null || ay == null || az == null || bx == null || by == null || bz == null) return 0.70f
        val distance = (abs(ax - bx) + abs(ay - by) + abs(az - bz)) / 3f
        return (1f - distance / 45f).coerceIn(0f, 1f)
    }
}
