package com.example.personalmemoryai.intelligence

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.indexing.OcrEngine
import com.example.personalmemoryai.indexing.YoloObjectDetector
import com.example.personalmemoryai.semantic.SemanticSearchService
import com.example.personalmemoryai.vision.FaceSearchService

/**
 * Stage-5 evidence fusion engine.
 *
 * It combines independently measured face, body, pose, clothing/color, scene,
 * semantic-visual, OCR and object evidence. Missing evidence is excluded rather
 * than silently converted into a zero score. Scores are similarity estimates,
 * not proof of real-world identity.
 */
class CompositeEvidenceMatcher(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val semantic = SemanticSearchService(appContext)
    private val ocr = OcrEngine(appContext)
    private val objectDetector = YoloObjectDetector(appContext)
    private val faceSearch = FaceSearchService(appContext)
    private val bodyPose = BodyPoseEvidenceAnalyzer(appContext)
    private val appearance = VisualAppearanceAnalyzer(appContext)
    private val diagnostics = DiagnosticsManager.get(appContext)

    suspend fun search(queryUri: Uri, limit: Int = 30): List<CompositeMatch> {
        val run = diagnostics.begin("COMPOSITE_MATCH", mapOf("limit" to limit.toString()))
        try {
            run.stage("QUERY_OCR", "Extracting query text evidence")
            val queryOcr = ocr.process(queryUri)

            run.stage("QUERY_OBJECTS", "Extracting query object evidence")
            val queryObjects = try { objectDetector.detect(queryUri) } catch (t: Throwable) {
                run.failure("QUERY_OBJECTS", t); emptyList()
            }

            run.stage("QUERY_FACE", "Running multi-model face retrieval")
            val faceMatches = try { faceSearch.search(queryUri, limit.coerceAtLeast(50)) } catch (t: Throwable) {
                run.warning("Face evidence unavailable: ${t.message ?: t.javaClass.simpleName}"); emptyList()
            }
            val faceByImage = faceMatches.groupBy { it.image.id }.mapValues { (_, values) -> values.maxOf { it.compositeScore } }

            run.stage("QUERY_BODY_POSE", "Extracting real 33-landmark body pose evidence")
            val queryPose = try { bodyPose.analyze(queryUri) } catch (t: Throwable) {
                run.warning("Body/pose evidence unavailable: ${t.message ?: t.javaClass.simpleName}"); null
            }
            run.stage("QUERY_APPEARANCE", "Extracting clothing/color and scene-layout evidence")
            val queryAppearance = try { appearance.analyze(queryUri) } catch (t: Throwable) {
                run.warning("Appearance evidence unavailable: ${t.message ?: t.javaClass.simpleName}"); null
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
            val bodyByImage = mutableMapOf<Long, BodyPoseEvidenceAnalyzer.Result>()
            val appearanceByImage = mutableMapOf<Long, VisualAppearanceAnalyzer.Descriptor>()

            for (visual in visualCandidates) {
                val image = images[visual.image.id] ?: continue
                try { bodyPose.analyze(Uri.parse(image.uri))?.let { bodyByImage[image.id] = it } } catch (t: Throwable) {
                    run.warning("Body/pose failed for image ${image.id}: ${t.message ?: t.javaClass.simpleName}")
                }
                try { appearance.analyze(Uri.parse(image.uri))?.let { appearanceByImage[image.id] = it } } catch (t: Throwable) {
                    run.warning("Appearance analysis failed for image ${image.id}: ${t.message ?: t.javaClass.simpleName}")
                }
            }

            val matches = visualCandidates.mapNotNull { visual ->
                val image = images[visual.image.id] ?: return@mapNotNull null
                val text = textSimilarity(queryTokens, textTokens(image.ocrText))
                val objects = objectSimilarity(queryObjectLabels, parseObjectLabels(image.detectedObjects))
                val quality = imageEvidenceQuality(image)
                val visualScore = normalizeCosine(visual.score)
                val faceScore = faceByImage[image.id]
                val bodyScore = if (queryPose != null) bodyByImage[image.id]?.let { bodyPose.similarity(queryPose, it) } else null
                val sceneScore = if (queryAppearance != null) appearanceByImage[image.id]?.let { appearance.sceneSimilarity(queryAppearance, it) } else null
                val clothingScore = if (queryAppearance != null) appearanceByImage[image.id]?.let { appearance.colorSimilarity(queryAppearance, it) } else null

                val evidence = linkedMapOf<String, EvidenceComponent>()
                evidence["visual"] = EvidenceComponent(visualScore, true, "MobileCLIP-S2 semantic/global visual similarity", 0.20f)
                evidence["face"] = EvidenceComponent(faceScore ?: 0f, faceScore != null, "FaceSearchService: MobileFaceNet + FaceNet-512 + landmarks + face pose", 0.25f)
                evidence["body"] = EvidenceComponent(bodyScore ?: 0f, bodyScore != null, "ML Kit 33-landmark normalized body descriptor", 0.15f)
                evidence["pose"] = EvidenceComponent(bodyScore ?: 0f, bodyScore != null, "ML Kit pose geometry", 0.10f)
                evidence["clothing"] = EvidenceComponent(clothingScore ?: 0f, clothingScore != null, "HSV color/spatial appearance descriptor", 0.08f)
                evidence["scene"] = EvidenceComponent(sceneScore ?: 0f, sceneScore != null, "Spatial color + edge + aspect scene descriptor", 0.07f)
                evidence["ocr"] = EvidenceComponent(text, queryTokens.isNotEmpty() && image.ocrText.isNotBlank(), "OCR token overlap", 0.05f)
                evidence["objects"] = EvidenceComponent(objects, queryObjectLabels.isNotEmpty() && image.detectedObjects.isNotBlank(), "Object-label overlap", 0.05f)
                evidence["quality"] = EvidenceComponent(quality, true, "Indexed evidence quality", 0.05f)

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

            run.stage("FUSION", "Face/body/pose/clothing/scene/visual/text/object evidence fused", mapOf(
                "candidates" to matches.size.toString(),
                "queryObjects" to queryObjectLabels.size.toString(),
                "faceEvidence" to faceByImage.size.toString(),
                "bodyEvidence" to bodyByImage.size.toString(),
                "appearanceEvidence" to appearanceByImage.size.toString(),
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

    private fun confidence(score: Float, coverage: Float, quality: Float): Float =
        (score * 0.70f + coverage * 0.20f + quality * 0.10f).coerceIn(0f, 1f)

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
        faceSearch.close()
        bodyPose.close()
    }
}
