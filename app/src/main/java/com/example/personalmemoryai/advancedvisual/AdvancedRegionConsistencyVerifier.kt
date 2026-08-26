package com.example.personalmemoryai.advancedvisual

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Deterministic, model-free spatial verifier for Advanced Visual Intelligence. */
class AdvancedRegionConsistencyVerifier {
    data class Score(
        val similarity: Float,
        val structure: Float,
        val spatialColor: Float,
        val spatialTexture: Float,
        val layout: Float,
        val stableRegionRatio: Float,
        val disagreementPenalty: Float,
        val evidence: List<String>
    )

    fun compare(
        a: AdvancedVisualFingerprintEngine.Fingerprint,
        b: AdvancedVisualFingerprintEngine.Fingerprint
    ): Score {
        val structure = spatialByteSimilarity(to8x8(a.grayPyramid), to8x8(b.grayPyramid))
        val spatialColor = spatialByteSimilarity(a.spatialColor, b.spatialColor)
        val spatialTexture = spatialByteSimilarity(a.spatialLbp, b.spatialLbp)
        val layout = spatialByteSimilarity(a.layoutSignature, b.layoutSignature)

        val aGray = to8x8(a.grayPyramid)
        val bGray = to8x8(b.grayPyramid)
        val aLayout = a.layoutSignature
        val bLayout = b.layoutSignature
        val cells = minOf(64, aGray.size, bGray.size, aLayout.size, bLayout.size).coerceAtLeast(1)
        var stable = 0
        for (i in 0 until cells) {
            val ds = normalizedByteDistance(aGray[i], bGray[i])
            val dl = normalizedByteDistance(aLayout[i], bLayout[i])
            if (ds <= 0.22f && dl <= 0.28f) stable++
        }
        val stableRatio = stable.toFloat() / cells.toFloat()

        val disagreement = max(
            abs(structure - spatialColor),
            max(abs(structure - spatialTexture), abs(layout - structure))
        ).coerceIn(0f, 1f)

        var similarity = (
            structure * 0.38f +
            spatialColor * 0.24f +
            spatialTexture * 0.20f +
            layout * 0.18f
        ).coerceIn(0f, 1f)

        if (stableRatio < 0.25f && similarity > 0.58f) similarity *= 0.82f
        if (disagreement > 0.38f && similarity > 0.62f) similarity *= 0.88f
        if (stableRatio >= 0.62f && disagreement <= 0.20f) similarity = min(1f, similarity + 0.035f)

        val evidence = buildList {
            if (stableRatio >= 0.75f) add("strong_region_alignment")
            else if (stableRatio >= 0.55f) add("good_region_alignment")
            else if (stableRatio < 0.25f) add("weak_region_alignment")
            if (disagreement <= 0.18f) add("spatial_signal_agreement")
            if (disagreement >= 0.38f) add("spatial_signal_disagreement")
            if (spatialColor >= 0.78f && structure < 0.48f) add("regional_color_structure_conflict")
            if (spatialTexture >= 0.78f && structure < 0.48f) add("regional_texture_structure_conflict")
        }
        return Score(
            similarity = similarity,
            structure = structure,
            spatialColor = spatialColor,
            spatialTexture = spatialTexture,
            layout = layout,
            stableRegionRatio = stableRatio,
            disagreementPenalty = disagreement,
            evidence = evidence
        )
    }

    private fun to8x8(source: ByteArray): ByteArray {
        if (source.size == 64) return source
        if (source.size != 256) return source
        val out = ByteArray(64)
        for (gy in 0 until 8) for (gx in 0 until 8) {
            var sum = 0
            for (dy in 0 until 2) for (dx in 0 until 2) {
                val i = (gy * 2 + dy) * 16 + (gx * 2 + dx)
                sum += source[i].toInt() and 0xFF
            }
            out[gy * 8 + gx] = (sum / 4).coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun spatialByteSimilarity(a: ByteArray, b: ByteArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var distance = 0.0
        for (i in 0 until n) distance += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) / 255.0
        return (1.0 - distance / n).toFloat().coerceIn(0f, 1f)
    }

    private fun normalizedByteDistance(a: Byte, b: Byte): Float =
        abs((a.toInt() and 0xFF) - (b.toInt() and 0xFF)) / 255f
}
