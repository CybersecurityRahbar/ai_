package com.example.personalmemoryai.advancedvisual

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Independent Advanced Visual Intelligence search.
 *
 * The Advanced section intentionally does NOT call the classical ReverseImageSearchService
 * to rank candidates. It uses only the Advanced visual corpus, then applies spatial-region
 * and multiscale structural verification before its final Fusion V4 score.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedVisualIntelligenceService(context: android.content.Context) : AutoCloseable {
    companion object {
        const val FUSION_VERSION = "ADVANCED-VISUAL-FUSION-V4"
        const val QUERY_VARIANTS = 7
        private const val CANDIDATE_LIMIT = 64
        private const val PARALLELISM = 4
    }

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val advancedDao = database.advancedVisualFingerprintDao()
    private val itemDao = database.reverseImageItemDao()
    private val engine = AdvancedVisualFingerprintEngine()
    private val regionVerifier = AdvancedRegionConsistencyVerifier()
    private val structuralConsensus = AdvancedStructuralConsensusEngine()
    private val cpuDispatcher = Dispatchers.Default.limitedParallelism(PARALLELISM)

    data class Evidence(
        val itemId: Long,
        val displayName: String,
        val filePath: String?,
        val finalSimilarity: Float,
        val finalPercent: Int,
        val confidencePercent: Int,
        val baseClassicalPercent: Int,
        val haarPercent: Int,
        val phashPercent: Int,
        val dhashPercent: Int,
        val colorPercent: Int,
        val edgePercent: Int,
        val localPercent: Int,
        val ransacInliers: Int,
        val advancedPercent: Int,
        val structurePercent: Int,
        val advancedColorPercent: Int,
        val spatialColorPercent: Int,
        val texturePercent: Int,
        val spatialTexturePercent: Int,
        val gradientPercent: Int,
        val gradientMagnitudePercent: Int,
        val layoutPercent: Int,
        val illuminationPercent: Int,
        val entropyPercent: Int,
        val aspectPercent: Int,
        val regionConsistencyPercent: Int,
        val stableRegionPercent: Int,
        val spatialDisagreementPercent: Int,
        val structuralConsensusPercent: Int,
        val coarseStructurePercent: Int,
        val fineStructurePercent: Int,
        val bestQueryVariant: String,
        val evidenceReasons: List<String>
    )

    private data class QueryVariant(
        val label: String,
        val fingerprint: AdvancedVisualFingerprintEngine.Fingerprint
    )

    private data class Candidate(
        val itemId: Long,
        val variantIndex: Int,
        val score: AdvancedVisualFingerprintEngine.Score
    )

    suspend fun fingerprintCount(): Long = withContext(Dispatchers.IO) { advancedDao.count() }

    suspend fun search(
        queryUri: Uri,
        limit: Int = 50,
        minimumSimilarity: Float = 0.35f,
        onProgress: (processed: Int, total: Int, stage: String) -> Unit = { _, _, _ -> }
    ): List<Evidence> = withContext(Dispatchers.Default) {
        val resolver = appContext.contentResolver
        val original = resolver.openInputStream(queryUri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "تعذر قراءة صورة البحث المتقدم: $queryUri" }
        }
        try {
            val variants = buildQueryVariants(original)
            onProgress(0, QUERY_VARIANTS, "$FUSION_VERSION • تحضير $QUERY_VARIANTS نسخ استعلام")

            val stored = withContext(Dispatchers.IO) {
                advancedDao.getAll(AdvancedVisualFingerprintEngine.ENGINE_VERSION)
            }
            if (stored.isEmpty()) return@withContext emptyList()

            val fingerprints = stored.associate { it.itemId to it.toFingerprint() }
            val bestById = HashMap<Long, Candidate>(stored.size)

            for ((variantIndex, variant) in variants.withIndex()) {
                coroutineContext.ensureActive()
                val scored = coroutineScope {
                    stored.chunked(128).map { chunk ->
                        async(cpuDispatcher) {
                            chunk.map { entity ->
                                val target = fingerprints[entity.itemId] ?: return@map null
                                Candidate(entity.itemId, variantIndex, engine.compare(variant.fingerprint, target))
                            }.filterNotNull()
                        }
                    }.awaitAll().flatten()
                }
                for (candidate in scored) {
                    val current = bestById[candidate.itemId]
                    if (current == null || candidate.score.similarity > current.score.similarity) {
                        bestById[candidate.itemId] = candidate
                    }
                }
                onProgress(
                    variantIndex + 1,
                    QUERY_VARIANTS,
                    "$FUSION_VERSION • ${variant.label} • full advanced corpus"
                )
            }

            val topCandidates = bestById.values
                .sortedWith(compareByDescending<Candidate> { it.score.similarity }
                    .thenByDescending { it.score.structure }
                    .thenByDescending { it.score.spatialColor }
                    .thenByDescending { it.score.spatialTexture })
                .take(CANDIDATE_LIMIT)

            val items = withContext(Dispatchers.IO) {
                itemDao.getByIds(topCandidates.map { it.itemId }).associateBy { it.id }
            }

            val results = coroutineScope {
                topCandidates.map { candidate ->
                    async(cpuDispatcher) {
                        coroutineContext.ensureActive()
                        val item = items[candidate.itemId] ?: return@async null
                        val target = fingerprints[candidate.itemId] ?: return@async null
                        val query = variants[candidate.variantIndex].fingerprint
                        val score = candidate.score
                        val region = regionVerifier.compare(query, target)
                        val structural = structuralConsensus.compare(query, target)
                        val coherence = signalCoherence(
                            score.structure,
                            score.spatialColor,
                            score.texture,
                            score.gradient,
                            score.layout,
                            score.illumination
                        )
                        val harmonic = harmonicMean(
                            score.structure,
                            score.spatialColor,
                            score.texture,
                            score.gradient,
                            score.layout
                        )

                        var final = (
                            score.similarity * 0.44f +
                                harmonic * 0.22f +
                                coherence * 0.10f +
                                region.similarity * 0.14f +
                                structural.similarity * 0.10f
                            ).coerceIn(0f, 1f)

                        if (coherence < 0.42f && final > 0.58f) final *= 0.82f
                        if (region.stableRegionRatio < 0.25f && final > 0.58f) final *= 0.84f
                        if (region.disagreementPenalty > 0.40f && final > 0.62f) final *= 0.88f
                        if (structural.fine < 0.42f && structural.coarse > 0.70f) final *= 0.87f
                        if (score.spatialColor > 0.82f && score.structure < 0.48f) final *= 0.80f
                        if (score.texture > 0.82f && score.structure < 0.45f) final *= 0.88f
                        if (score.structure >= 0.80f && score.gradient >= 0.74f && region.stableRegionRatio >= 0.65f && structural.similarity >= 0.72f) {
                            final = (final + 0.025f).coerceAtMost(1f)
                        }
                        final = final.coerceIn(0f, 1f)
                        if (final < minimumSimilarity) return@async null

                        val independent = listOf(
                            score.structure,
                            score.spatialColor,
                            score.texture,
                            score.gradient,
                            score.illumination,
                            region.similarity,
                            structural.similarity
                        )
                        val independentConsensus = independent.count { it >= 0.62f }
                        val confidence = (
                            independentConsensus / independent.size.toFloat() * 45f +
                                coherence * 20f +
                                region.stableRegionRatio * 20f +
                                structural.similarity * 15f
                            ).toInt().coerceIn(0, 100)

                        val variantLabel = variants[candidate.variantIndex].label
                        val reasons = buildList {
                            add("multi-scale structure ${pct(score.structure)}%")
                            add("spatial color ${pct(score.spatialColor)}%")
                            add("LBP texture ${pct(score.spatialTexture)}%")
                            add("gradient ${pct(score.gradient)}%")
                            add("layout ${pct(score.layout)}%")
                            add("illumination ${pct(score.illumination)}%")
                            add("region consistency ${pct(region.similarity)}%")
                            add("stable regions ${pct(region.stableRegionRatio)}%")
                            add("multiscale consensus ${pct(structural.similarity)}%")
                            add("signal coherence ${pct(coherence)}%")
                            if (independentConsensus >= 5) add("strong independent-signal consensus")
                            else if (independentConsensus >= 3) add("moderate independent-signal consensus")
                            else add("weak independent-signal consensus")
                            if (region.disagreementPenalty > 0.40f) add("spatial evidence conflict reduced the score")
                            if (structural.fine < 0.42f && structural.coarse > 0.70f) add("fine structure disagreed despite coarse agreement")
                            if (variantLabel != "original") add("best evidence came from query variant: $variantLabel")
                        }.distinct()

                        Evidence(
                            itemId = item.id,
                            displayName = item.displayName,
                            filePath = item.filePath,
                            finalSimilarity = final,
                            finalPercent = pct(final),
                            confidencePercent = confidence,
                            baseClassicalPercent = 0,
                            haarPercent = 0,
                            phashPercent = 0,
                            dhashPercent = 0,
                            colorPercent = 0,
                            edgePercent = 0,
                            localPercent = 0,
                            ransacInliers = 0,
                            advancedPercent = pct(score.similarity),
                            structurePercent = pct(score.structure),
                            advancedColorPercent = pct(score.color),
                            spatialColorPercent = pct(score.spatialColor),
                            texturePercent = pct(score.texture),
                            spatialTexturePercent = pct(score.spatialTexture),
                            gradientPercent = pct(score.gradient),
                            gradientMagnitudePercent = pct(score.gradientMagnitude),
                            layoutPercent = pct(score.layout),
                            illuminationPercent = pct(score.illumination),
                            entropyPercent = pct(score.entropy),
                            aspectPercent = pct(score.aspect),
                            regionConsistencyPercent = pct(region.similarity),
                            stableRegionPercent = pct(region.stableRegionRatio),
                            spatialDisagreementPercent = pct(region.disagreementPenalty),
                            structuralConsensusPercent = pct(structural.similarity),
                            coarseStructurePercent = pct(structural.coarse),
                            fineStructurePercent = pct(structural.fine),
                            bestQueryVariant = variantLabel,
                            evidenceReasons = reasons
                        )
                    }
                }.awaitAll().filterNotNull()
            }

            results.sortedWith(
                compareByDescending<Evidence> { it.finalSimilarity }
                    .thenByDescending { it.confidencePercent }
                    .thenByDescending { it.structuralConsensusPercent }
                    .thenByDescending { it.regionConsistencyPercent }
            ).take(limit)
        } finally {
            original.recycle()
        }
    }

    private fun buildQueryVariants(original: Bitmap): List<QueryVariant> {
        val result = ArrayList<QueryVariant>(QUERY_VARIANTS)
        result += QueryVariant("original", engine.fingerprint(original))
        for (angle in intArrayOf(90, 180, 270)) {
            val matrix = Matrix().apply { postRotate(angle.toFloat()) }
            val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            try {
                result += QueryVariant("rotation_$angle", engine.fingerprint(rotated))
            } finally {
                rotated.recycle()
            }
        }
        for (ratio in floatArrayOf(0.92f, 0.82f, 0.72f)) {
            val width = maxOf(1, (original.width * ratio).toInt())
            val height = maxOf(1, (original.height * ratio).toInt())
            val left = (original.width - width) / 2
            val top = (original.height - height) / 2
            val crop = Bitmap.createBitmap(original, left, top, width, height)
            try {
                result += QueryVariant("center_crop_${(ratio * 100).toInt()}", engine.fingerprint(crop))
            } finally {
                crop.recycle()
            }
        }
        return result
    }

    private fun AdvancedVisualFingerprintEntity.toFingerprint() = AdvancedVisualFingerprintEngine.Fingerprint(
        grayPyramid = grayPyramid,
        colorMoments = colorMoments,
        spatialColor = spatialColor,
        lbpHistogram = lbpHistogram,
        spatialLbp = spatialLbp,
        gradientHistogram = gradientHistogram,
        gradientMagnitude = gradientMagnitude,
        layoutSignature = layoutSignature,
        illuminationRobustStructure = illuminationRobustStructure,
        entropy = entropy,
        aspectRatio = aspectRatio
    )

    private fun pct(value: Float): Int = (value * 100f).toInt().coerceIn(0, 100)

    private fun harmonicMean(vararg values: Float): Float {
        if (values.isEmpty() || values.any { it <= 0f }) return 0f
        val denominator = values.sumOf { (1.0 / it.coerceAtLeast(1e-4f)).toDouble() }
        return (values.size.toDouble() / denominator).toFloat().coerceIn(0f, 1f)
    }

    private fun signalCoherence(vararg values: Float): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return (1f - sqrt(variance)).coerceIn(0f, 1f)
    }

    override fun close() = Unit
}
