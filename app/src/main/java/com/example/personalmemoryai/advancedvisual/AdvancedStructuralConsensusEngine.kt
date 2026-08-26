package com.example.personalmemoryai.advancedvisual

import kotlin.math.abs
import kotlin.math.min

/** Deterministic multi-resolution agreement check; no ML model and no additional stored index. */
class AdvancedStructuralConsensusEngine {
    data class Score(
        val similarity: Float,
        val coarse: Float,
        val fine: Float,
        val edgeLayout: Float,
        val evidence: List<String>
    )

    fun compare(a: AdvancedVisualFingerprintEngine.Fingerprint, b: AdvancedVisualFingerprintEngine.Fingerprint): Score {
        val coarse = pooledSimilarity(a.grayPyramid, b.grayPyramid, 16, 4)
        val fine = pooledSimilarity(a.grayPyramid, b.grayPyramid, 16, 2)
        val edgeLayout = byteSimilarity(a.layoutSignature, b.layoutSignature)
        var similarity = (coarse * 0.42f + fine * 0.38f + edgeLayout * 0.20f).coerceIn(0f, 1f)
        if (fine < 0.42f && coarse > 0.70f) similarity *= 0.86f
        if (coarse >= 0.75f && fine >= 0.70f && edgeLayout >= 0.68f) similarity = min(1f, similarity + 0.03f)
        val evidence = buildList {
            if (coarse >= 0.78f) add("strong_coarse_structure")
            if (fine >= 0.74f) add("strong_fine_structure")
            if (edgeLayout >= 0.75f) add("layout_edge_consensus")
            if (coarse >= 0.72f && fine >= 0.68f && edgeLayout >= 0.64f) add("multiscale_structural_consensus")
            if (fine < 0.42f && coarse > 0.70f) add("fine_structure_conflict")
        }
        return Score(similarity, coarse, fine, edgeLayout, evidence)
    }

    private fun pooledSimilarity(a: ByteArray, b: ByteArray, sourceGrid: Int, pool: Int): Float {
        if (a.size != sourceGrid * sourceGrid || b.size != sourceGrid * sourceGrid) return byteSimilarity(a, b)
        val targetGrid = sourceGrid / pool
        val aa = ByteArray(targetGrid * targetGrid)
        val bb = ByteArray(targetGrid * targetGrid)
        var k = 0
        for (gy in 0 until targetGrid) for (gx in 0 until targetGrid) {
            var sa = 0; var sb = 0
            for (dy in 0 until pool) for (dx in 0 until pool) {
                val i = (gy * pool + dy) * sourceGrid + (gx * pool + dx)
                sa += a[i].toInt() and 0xFF
                sb += b[i].toInt() and 0xFF
            }
            val n = pool * pool
            aa[k] = (sa / n).coerceIn(0, 255).toByte()
            bb[k] = (sb / n).coerceIn(0, 255).toByte()
            k++
        }
        return byteSimilarity(aa, bb)
    }

    private fun byteSimilarity(a: ByteArray, b: ByteArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var d = 0.0
        for (i in 0 until n) d += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) / 255.0
        return (1.0 - d / n).toFloat().coerceIn(0f, 1f)
    }
}
