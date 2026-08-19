package com.example.personalmemoryai.intelligence

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.indexing.OcrEngine
import com.example.personalmemoryai.indexing.YoloObjectDetector
import com.example.personalmemoryai.semantic.SemanticSearchService
import kotlin.math.sqrt

/**
 * Stage-5 evidence fusion engine.
 *
 * It combines independent evidence without pretending unavailable signals exist.
 * Scores are similarity estimates, not proof of real-world identity.
 */
class CompositeEvidenceMatcher(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val semantic = SemanticSearchService(appContext)
    private val ocr = OcrEngine(appContext)
    private val objectDetector = YoloObjectDetector(appContext)
    private val diagnostics = DiagnosticsManager.get(appContext)

    suspend fun search(queryUri: Uri, limit: Int = 30): List<CompositeMatch> {
        val run = diagnostics.begin("COMPOSITE_MATCH", mapOf("limit" to limit.toString()))
        try {
            run.stage("QUERY_OCR", "Extracting query text evidence")
            val queryOcr = ocr.process(queryUri)

            run.stage("QUERY_OBJECTS", "Extracting query object evidence")
            val queryObjects = try {
                objectDetector.detect(queryUri)
            } catch (t: Throwable) {
                run.failure("QUERY_OBJECTS", t)
                emptyList()
            }

            run.stage("QUERY_VISUAL", "Generating query visual candidates")
            val visualCandidates = semantic.searchSimilarImages(queryUri, limit.coerceAtLeast(50))
            if (visualCandidates.isEmpty()) {
                run.warning("No visual candidates available")
                return emptyList()
            }

            val queryTokens = textTokens(queryOcr.text)
            val queryObjectLabels = queryObjects.map { it.label.lowercase() }.toSet()
            val candidateIds = visualCandidates.map { it.image.id }
            val images = database.imageDao().getByIds(candidateIds).associateBy { it.id }

            val matches = visualCandidates.mapNotNull { visual ->
                val image = images[visual.image.id] ?: return@mapNotNull null
                val text = textSimilarity(queryTokens, textTokens(image.ocrText))
                val objects = objectSimilarity(queryObjectLabels, parseObjectLabels(image.detectedObjects))
                val quality = imageEvidenceQuality(image)
                val visualScore = normalizeCosine(visual.score)
                val faceEvidence = faceEvidenceForImage(image.id)
                val evidence = linkedMapOf<String, EvidenceComponent>()
                evidence["visual"] = EvidenceComponent(visualScore, true, "MobileCLIP-S2", 0.35f)
                evidence["ocr"] = EvidenceComponent(text, queryTokens.isNotEmpty() && image.ocrText.isNotBlank(), "OCR token overlap", 0.10f)
                evidence["objects"] = EvidenceComponent(objects, queryObjectLabels.isNotEmpty() && image.detectedObjects.isNotBlank(), "Object-label overlap", 0.10f)
                evidence["quality"] = EvidenceComponent(quality, true, "Indexed evidence quality", 0.05f)
                evidence["face"] = faceEvidence
                evidence["body"] = EvidenceComponent(0f, false, "Body descriptor unavailable", 0.15f)
                evidence["pose"] = EvidenceComponent(0f, false, "Pose descriptor unavailable", 0.10f)
                evidence["scene"] = EvidenceComponent(0f, false, "Scene descriptor unavailable", 0.05f)

                val active = evidence.values.filter { it.available && it.reliability > 0f }
                val weightedTotal = active.sumOf { (it.score * it.weight * it.reliability).toDouble() }.toFloat()
                val weightTotal = active.sumOf { (it.weight * it.reliability).toDouble() }.toFloat()
                val score = if (weightTotal <= 0f) 0f else (weightedTotal / weightTotal).coerceIn(0f, 1f)
                val coverage = if (evidence.isEmpty()) 0f else active.sumOf { (it.weight * it.reliability).toDouble() }.toFloat() /
                    evidence.values.sumOf { it.weight.toDouble() }.toFloat()
                val confidence = confidence(score, coverage, quality)
                CompositeMatch(
                    image = image,
                    compositeScore = score,
                    band = band(confidence),
                    confidence = confidence,
                    evidenceCoverage = coverage,
                    visualScore = visualScore,
                    ocrScore = text,
                    objectScore = objects,
                    evidenceQuality = quality,
                    components = evidence
                )
            }.sortedWith(compareByDescending<CompositeMatch> { it.confidence }.thenByDescending { it.compositeScore }).take(limit)

            run.stage("FUSION", "Evidence components fused", mapOf(
                "candidates" to matches.size.toString(),
                "queryObjects" to queryObjectLabels.size.toString(),
                "topScore" to (matches.firstOrNull()?.compositeScore ?: 0f).toString(),
                "topConfidence" to (matches.firstOrNull()?.confidence ?: 0f).toString(),
                "coverage" to (matches.firstOrNull()?.evidenceCoverage ?: 0f).toString()
            ))
            run.success("Composite evidence search completed", mapOf("results" to matches.size.toString()))
            return matches
        } catch (t: Throwable) {
            run.failure("COMPOSITE_PIPELINE", t)
            throw t
        }
    }

    private suspend fun faceEvidenceForImage(imageId: Long): EvidenceComponent {
        val faces = database.faceDao().getByImageId(imageId)
        val usable = faces.filter { it.usableForMatching && it.hasEmbedding }
        if (usable.isEmpty()) return EvidenceComponent(0f, false, "No persisted usable face embedding", 0.20f)
        // Candidate-side face evidence is intentionally marked available only as a
        // reliability signal here; a query-face vector must be supplied by FaceSearchService
        // for an actual face-to-face similarity score.
        val bestQuality = usable.maxOf { it.qualityScore }.coerceIn(0f, 1f)
        return EvidenceComponent(bestQuality, true, "Persisted face quality; query-face similarity not supplied", 0.20f, bestQuality * 0.25f)
    }

    private fun normalizeCosine(score: Float): Float = ((score + 1f) / 2f).coerceIn(0f, 1f)

    private fun textTokens(text: String): Set<String> = text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(Regex("\\s+"))
        .map { it.trim() }.filter { it.length >= 2 }.toSet()

    private fun textSimilarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return a.intersect(b).size.toFloat() / a.union(b).size.toFloat()
    }

    private fun parseObjectLabels(serialized: String): Set<String> = serialized.split(';').mapNotNull { token ->
        token.substringBefore(':').substringBefore('|').trim().lowercase().takeIf { it.isNotBlank() }
    }.toSet()

    private fun objectSimilarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return a.intersect(b).size.toFloat() / a.union(b).size.toFloat()
    }

    private fun imageEvidenceQuality(image: ImageEntity): Float {
        val ocrQuality = image.ocrQualityScore.coerceIn(0f, 1f)
        val dimensions = if (image.width > 0 && image.height > 0) 1f else 0f
        val objectEvidence = if (image.detectedObjects.isNotBlank()) 1f else 0f
        return (ocrQuality * 0.45f + dimensions * 0.35f + objectEvidence * 0.20f).coerceIn(0f, 1f)
    }

    private fun confidence(score: Float, coverage: Float, quality: Float): Float {
        // Confidence is intentionally penalized when major evidence families are absent.
        return (score * 0.70f + coverage * 0.20f + quality * 0.10f).coerceIn(0f, 1f)
    }

    private fun band(score: Float): MatchBand = when {
        score >= 0.90f -> MatchBand.VERY_HIGH
        score >= 0.78f -> MatchBand.HIGH
        score >= 0.62f -> MatchBand.MEDIUM
        score >= 0.45f -> MatchBand.LOW
        else -> MatchBand.VERY_LOW
    }

    enum class MatchBand { VERY_HIGH, HIGH, MEDIUM, LOW, VERY_LOW }

    data class EvidenceComponent(
        val score: Float,
        val available: Boolean,
        val source: String,
        val weight: Float,
        val reliability: Float = if (available) 1f else 0f
    )

    data class CompositeMatch(
        val image: ImageEntity,
        val compositeScore: Float,
        val band: MatchBand,
        val confidence: Float,
        val evidenceCoverage: Float,
        val visualScore: Float,
        val ocrScore: Float,
        val objectScore: Float,
        val evidenceQuality: Float,
        val components: Map<String, EvidenceComponent>
    )

    override fun close() {
        semantic.close()
        ocr.close()
        objectDetector.close()
    }
}
