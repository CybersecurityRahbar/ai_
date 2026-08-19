package com.example.personalmemoryai.intelligence

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.indexing.OcrEngine
import com.example.personalmemoryai.semantic.SemanticSearchService
import kotlin.math.sqrt

/**
 * Stage-5 evidence fusion engine.
 *
 * This deliberately does not claim real-world identity. It combines independent
 * visual/textual evidence and reports each component separately so a high score
 * cannot hide a weak or unavailable signal.
 */
class CompositeEvidenceMatcher(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val semantic = SemanticSearchService(appContext)
    private val ocr = OcrEngine(appContext)
    private val diagnostics = DiagnosticsManager.get(appContext)

    suspend fun search(queryUri: Uri, limit: Int = 30): List<CompositeMatch> {
        val run = diagnostics.begin("COMPOSITE_MATCH", mapOf("limit" to limit.toString()))
        try {
            run.stage("QUERY_OCR", "Extracting query text evidence")
            val queryOcr = ocr.process(queryUri)
            run.stage("QUERY_VISUAL", "Generating query visual candidates")
            val visualCandidates = semantic.searchSimilarImages(queryUri, limit.coerceAtLeast(30))
            if (visualCandidates.isEmpty()) {
                run.warning("No visual candidates available")
                return emptyList()
            }

            val queryTokens = textTokens(queryOcr.text)
            val candidateIds = visualCandidates.map { it.image.id }
            val images = database.imageDao().getByIds(candidateIds).associateBy { it.id }
            val matches = visualCandidates.mapNotNull { visual ->
                val image = images[visual.image.id] ?: return@mapNotNull null
                val text = textSimilarity(queryTokens, textTokens(image.ocrText))
                val objects = objectSimilarity(visual.image.detectedObjects, image.detectedObjects)
                val quality = imageEvidenceQuality(image)
                val visualScore = normalizeCosine(visual.score)

                // Face/body/pose/scene are explicit slots. They are not fabricated when
                // the corresponding extractor has no evidence; the fusion denominator
                // only uses signals that are actually available.
                val components = linkedMapOf(
                    "visual" to EvidenceComponent(visualScore, true, "MobileCLIP-S2"),
                    "ocr" to EvidenceComponent(text, queryTokens.isNotEmpty() && image.ocrText.isNotBlank(), "OCR token overlap"),
                    "objects" to EvidenceComponent(objects, image.detectedObjects.isNotBlank(), "Detected-object overlap"),
                    "quality" to EvidenceComponent(quality, true, "Indexed evidence quality"),
                    "face" to EvidenceComponent(0f, false, "Face matcher not supplied to fusion"),
                    "body" to EvidenceComponent(0f, false, "Body descriptor not supplied to fusion"),
                    "pose" to EvidenceComponent(0f, false, "Pose descriptor not supplied to fusion"),
                    "scene" to EvidenceComponent(0f, false, "Scene descriptor not supplied to fusion")
                )
                val active = components.values.filter { it.available }
                val score = if (active.isEmpty()) 0f else active.map { it.score }.average().toFloat()
                CompositeMatch(image, score, band(score), visualScore, text, objects, quality, components)
            }.sortedByDescending { it.compositeScore }.take(limit)

            run.stage("FUSION", "Evidence components fused", mapOf(
                "candidates" to matches.size.toString(),
                "topScore" to (matches.firstOrNull()?.compositeScore ?: 0f).toString(),
                "faceEvidence" to "unavailable-until-face-fusion-input"
            ))
            run.success("Composite evidence search completed", mapOf("results" to matches.size.toString()))
            return matches
        } catch (t: Throwable) {
            run.failure("COMPOSITE_PIPELINE", t)
            throw t
        }
    }

    private fun normalizeCosine(score: Float): Float = ((score + 1f) / 2f).coerceIn(0f, 1f)

    private fun textTokens(text: String): Set<String> = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
        .toSet()

    private fun textSimilarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat()
        return if (union == 0f) 0f else intersection / union
    }

    private fun objectSimilarity(a: String, b: String): Float {
        val left = a.split(';').mapNotNull { it.substringBefore(':').substringBefore('|').trim().lowercase().takeIf(String::isNotBlank) }.toSet()
        val right = b.split(';').mapNotNull { it.substringBefore(':').substringBefore('|').trim().lowercase().takeIf(String::isNotBlank) }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0f
        return left.intersect(right).size.toFloat() / left.union(right).size.toFloat()
    }

    private fun imageEvidenceQuality(image: ImageEntity): Float {
        val ocrQuality = image.ocrQualityScore.coerceIn(0f, 1f)
        val dimensions = if (image.width > 0 && image.height > 0) 1f else 0f
        val objectEvidence = if (image.detectedObjects.isNotBlank()) 1f else 0f
        return (ocrQuality * 0.45f + dimensions * 0.35f + objectEvidence * 0.20f).coerceIn(0f, 1f)
    }

    private fun band(score: Float): MatchBand = when {
        score >= 0.90f -> MatchBand.VERY_HIGH
        score >= 0.78f -> MatchBand.HIGH
        score >= 0.62f -> MatchBand.MEDIUM
        score >= 0.45f -> MatchBand.LOW
        else -> MatchBand.VERY_LOW
    }

    enum class MatchBand { VERY_HIGH, HIGH, MEDIUM, LOW, VERY_LOW }

    data class EvidenceComponent(val score: Float, val available: Boolean, val source: String)

    data class CompositeMatch(
        val image: ImageEntity,
        val compositeScore: Float,
        val band: MatchBand,
        val visualScore: Float,
        val ocrScore: Float,
        val objectScore: Float,
        val evidenceQuality: Float,
        val components: Map<String, EvidenceComponent>
    )

    override fun close() {
        semantic.close()
        ocr.close()
    }
}
