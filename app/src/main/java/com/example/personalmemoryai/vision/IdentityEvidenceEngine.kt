package com.example.personalmemoryai.vision

import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceEntity
import kotlin.math.abs

/** Conservative multi-model evidence combiner for visual association. */
object IdentityEvidenceEngine {
    data class Template(
        val face: FaceEntity,
        val identity: EmbeddingEntity?,
        val shape: EmbeddingEntity?,
        val secondaryIdentity: EmbeddingEntity? = null
    )

    data class Evidence(
        val composite: Float,
        val identity: Float,
        val shape: Float,
        val pose: Float,
        val quality: Float,
        val corroboration: Float,
        val templateCount: Int,
        val secondaryIdentity: Float = 0f,
        val modelAgreement: Float = 0f
    )

    fun compare(query: FaceEntity, queryIdentity: EmbeddingEntity?, queryShape: EmbeddingEntity?, templates: List<Template>, querySecondaryIdentity: EmbeddingEntity? = null): Evidence? {
        if (templates.isEmpty()) return null
        val scored = templates.mapNotNull { template ->
            val identity = similarity(queryIdentity, template.identity)
            val secondary = similarity(querySecondaryIdentity, template.secondaryIdentity)
            val shape = if (queryShape != null && template.shape != null) similarity(queryShape, template.shape) else null
            val pose = poseCompatibility(query, template.face)
            val quality = ((query.qualityScore.coerceIn(0f, 1f) + template.face.qualityScore.coerceIn(0f, 1f)) * 0.5f)
            if (identity == null && secondary == null && shape == null) null else {
                val modelCount = listOfNotNull(identity, secondary).size
                val modelAgreement = if (identity != null && secondary != null) (1f - abs(identity - secondary)).coerceIn(0f, 1f) else 0.75f
                val learnedIdentity = when {
                    identity != null && secondary != null -> 0.45f * identity + 0.55f * secondary
                    secondary != null -> secondary
                    identity != null -> identity
                    else -> shape!!
                }
                val visual = if (shape != null) 0.82f * learnedIdentity + 0.18f * shape else learnedIdentity
                val adjusted = (visual * (0.88f + 0.12f * quality) * (0.92f + 0.08f * pose) * (if (modelCount == 2) 1f else 0.93f)).coerceIn(0f, 1f)
                Triple(adjusted, learnedIdentity, shape ?: 0f) to Triple(pose, quality, modelAgreement)
            }
        }.sortedByDescending { it.first.first }
        if (scored.isEmpty()) return null

        val top = scored[0]
        val second = scored.getOrNull(1)
        val corroboration = if (second != null) {
            val closeness = (1f - (top.first.first - second.first.first).coerceAtLeast(0f) / 0.25f).coerceIn(0f, 1f)
            0.65f * second.first.first + 0.35f * closeness
        } else 0f
        val finalScore = if (second != null) (0.84f * top.first.first + 0.16f * corroboration).coerceIn(0f, 1f) else top.first.first
        return Evidence(finalScore, top.first.second, top.first.third, top.second.first, top.second.second, corroboration, scored.size, top.first.second, top.second.third)
    }

    fun shouldAssociate(evidence: Evidence): Boolean {
        if (evidence.secondaryIdentity >= 0.88f && evidence.modelAgreement >= 0.80f && evidence.quality >= 0.35f) return true
        if (evidence.identity >= 0.90f && evidence.quality >= 0.35f) return true
        if (evidence.identity >= 0.82f && evidence.shape >= 0.86f && evidence.corroboration >= 0.70f) return true
        if (evidence.identity >= 0.76f && evidence.shape >= 0.90f && evidence.pose >= 0.55f && evidence.quality >= 0.45f) return true
        if (evidence.identity >= 0.84f && evidence.composite >= 0.82f) return true
        return evidence.composite >= 0.89f && evidence.quality >= 0.55f && evidence.corroboration >= 0.70f
    }

    private fun similarity(a: EmbeddingEntity?, b: EmbeddingEntity?): Float? {
        if (a == null || b == null || a.dimension != b.dimension || a.vector.isEmpty() || b.vector.isEmpty()) return null
        if (!a.modelName.equals(b.modelName, ignoreCase = true) || a.modelVersion != b.modelVersion) return null
        if (!a.vector.all { it.isFinite() } || !b.vector.all { it.isFinite() }) return null
        return ((FaceSimilarity.cosineSimilarity(a.vector, b.vector) + 1f) / 2f).coerceIn(0f, 1f)
    }

    private fun poseCompatibility(a: FaceEntity, b: FaceEntity): Float {
        val ax = a.rotationX; val ay = a.rotationY; val az = a.rotationZ
        val bx = b.rotationX; val by = b.rotationY; val bz = b.rotationZ
        if (ax == null || ay == null || az == null || bx == null || by == null || bz == null) return 0.70f
        val distance = (abs(ax - bx) + abs(ay - by) + abs(az - bz)) / 3f
        return (1f - distance / 45f).coerceIn(0f, 1f)
    }
}
