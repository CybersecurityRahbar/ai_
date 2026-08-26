package com.example.personalmemoryai.advancedvisual

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.reverseimage.ReverseImageSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Independent Advanced search service with multi-variant query analysis and explainable fusion. */
class AdvancedVisualIntelligenceService(context: android.content.Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val advancedDao = database.advancedVisualFingerprintDao()
    private val itemDao = database.reverseImageItemDao()
    private val engine = AdvancedVisualFingerprintEngine()
    private val regionVerifier = AdvancedRegionConsistencyVerifier()

    data class Evidence(
        val itemId: Long,
        val displayName: String,
        val filePath: String?,
        val finalSimilarity: Float,
        val finalPercent: Int,
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
        val bestQueryVariant: String,
        val evidenceReasons: List<String>
    )

    private data class QueryVariant(val label: String, val fingerprint: AdvancedVisualFingerprintEngine.Fingerprint, val bitmap: Bitmap?)

    suspend fun fingerprintCount(): Long = withContext(Dispatchers.IO) { advancedDao.count() }

    suspend fun search(
        queryUri: Uri,
        limit: Int = 50,
        minimumSimilarity: Float = 0.35f,
        onProgress: (processed: Int, total: Int, stage: String) -> Unit = { _, _, _ -> }
    ): List<Evidence> = withContext(Dispatchers.Default) {
        val original = appContext.contentResolver.openInputStream(queryUri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "تعذر قراءة صورة البحث المتقدم: $queryUri" }
        }
        try {
            val variants = buildQueryVariants(original)
            onProgress(0, variants.size, "تحضير ${variants.size} نسخ استعلام Advanced V2")
            val stored = withContext(Dispatchers.IO) { advancedDao.getAll(AdvancedVisualFingerprintEngine.ENGINE_VERSION) }
            if (stored.isEmpty()) return@withContext emptyList()

            val storedById = stored.associateBy { it.itemId }
            val storedFingerprints = stored.associate { it.itemId to it.toFingerprint() }
            val baseResults = runCatching {
                ReverseImageSearchService(appContext).use { service -> service.search(queryUri, limit = 64, minimumSimilarity = 0f) }
            }.getOrDefault(emptyList())
            val baseById = baseResults.associateBy { it.item.id }

            val bestById = HashMap<Long, Pair<Int, AdvancedVisualFingerprintEngine.Score>>()
            for ((variantIndex, variant) in variants.withIndex()) {
                for (entity in stored) {
                    val score = engine.compare(variant.fingerprint, storedFingerprints[entity.itemId] ?: entity.toFingerprint())
                    val current = bestById[entity.itemId]
                    if (current == null || score.similarity > current.second.similarity) bestById[entity.itemId] = variantIndex to score
                }
                onProgress(variantIndex + 1, variants.size, "Advanced V2 variant ${variant.label}")
            }

            variants.filter { it.bitmap != null && it.bitmap !== original }.forEach { it.bitmap!!.recycle() }

            val advancedTop = bestById.entries.sortedByDescending { it.value.second.similarity }.take(64)
            val candidateIds = LinkedHashSet<Long>().apply {
                baseResults.forEach { add(it.item.id) }
                advancedTop.forEach { add(it.key) }
            }
            val items = withContext(Dispatchers.IO) { itemDao.getByIds(candidateIds.toList()).associateBy { it.id } }

            return@withContext candidateIds.mapNotNull { itemId ->
                val item = items[itemId] ?: return@mapNotNull null
                val entry = bestById[itemId] ?: return@mapNotNull null
                val variantIndex = entry.first
                val score = entry.second
                val base = baseById[itemId]
                val targetFingerprint = storedFingerprints[itemId] ?: storedById[itemId]?.toFingerprint() ?: return@mapNotNull null
                val region = regionVerifier.compare(
                    variants.getOrNull(variantIndex)?.fingerprint ?: return@mapNotNull null,
                    targetFingerprint
                )
                val agreementSignals = listOf(score.structure, score.spatialColor, score.spatialTexture, score.gradient, score.illumination, region.similarity)
                val consensus = agreementSignals.count { it >= 0.62f }
                var final = if (base != null) base.similarity * 0.58f + score.similarity * 0.42f else score.similarity * 0.70f
                final = final * 0.90f + region.similarity * 0.10f
                if (consensus <= 1 && final > 0.55f) final *= 0.78f
                if (score.structure < 0.50f && score.illumination < 0.45f && score.spatialColor > 0.80f) final *= 0.82f
                if (score.texture > 0.80f && score.structure < 0.45f) final *= 0.90f
                if (base != null && base.ransacInliers == 0 && score.structure < 0.50f) final *= 0.90f
                if (region.stableRegionRatio < 0.25f && final > 0.58f) final *= 0.86f
                if (region.disagreementPenalty > 0.40f && final > 0.62f) final *= 0.90f
                if (consensus >= 4 && region.stableRegionRatio >= 0.60f && score.gradient >= 0.65f && score.illumination >= 0.60f) final = (final + 0.025f).coerceAtMost(1f)
                final = final.coerceIn(0f, 1f)
                if (final < minimumSimilarity) return@mapNotNull null

                val variantLabel = variants.getOrNull(variantIndex)?.label ?: "original"
                val reasons = buildList {
                    addAll(score.evidence)
                    addAll(region.evidence)
                    if (base != null && base.percent >= 85) add("strong_existing_classical_agreement")
                    if (base != null && base.ransacInliers >= 4) add("existing_geometric_match")
                    if (score.spatialColor >= 0.78f) add("spatial_color_consistency")
                    if (score.spatialTexture >= 0.76f) add("regional_texture_consistency")
                    if (score.gradientMagnitude >= 0.78f) add("gradient_strength_consistency")
                    if (score.illumination >= 0.72f) add("illumination_robust_match")
                    if (consensus >= 4) add("strong_independent_signal_consensus")
                    if (consensus >= 3) add("independent_signal_consensus")
                    if (consensus <= 1) add("weak_cross_signal_consensus")
                    if (variantLabel != "original") add("best_match_from_query_variant")
                    if (base == null) add("advanced_only_candidate")
                    if (region.stableRegionRatio < 0.25f) add("low_stable_region_coverage")
                    if (region.disagreementPenalty > 0.40f) add("spatial_evidence_conflict")
                }.distinct()

                Evidence(
                    itemId=item.id, displayName=item.displayName, filePath=item.filePath,
                    finalSimilarity=final, finalPercent=(final*100f).toInt().coerceIn(0,100),
                    baseClassicalPercent=base?.percent ?: 0, haarPercent=base?.haarPercent ?: 0,
                    phashPercent=base?.phashPercent ?: 0, dhashPercent=base?.dhashPercent ?: 0,
                    colorPercent=base?.colorPercent ?: 0, edgePercent=base?.edgePercent ?: 0,
                    localPercent=base?.localPercent ?: 0, ransacInliers=base?.ransacInliers ?: 0,
                    advancedPercent=(score.similarity*100f).toInt().coerceIn(0,100),
                    structurePercent=(score.structure*100f).toInt().coerceIn(0,100),
                    advancedColorPercent=(score.color*100f).toInt().coerceIn(0,100),
                    spatialColorPercent=(score.spatialColor*100f).toInt().coerceIn(0,100),
                    texturePercent=(score.texture*100f).toInt().coerceIn(0,100),
                    spatialTexturePercent=(score.spatialTexture*100f).toInt().coerceIn(0,100),
                    gradientPercent=(score.gradient*100f).toInt().coerceIn(0,100),
                    gradientMagnitudePercent=(score.gradientMagnitude*100f).toInt().coerceIn(0,100),
                    layoutPercent=(score.layout*100f).toInt().coerceIn(0,100),
                    illuminationPercent=(score.illumination*100f).toInt().coerceIn(0,100),
                    entropyPercent=(score.entropy*100f).toInt().coerceIn(0,100),
                    aspectPercent=(score.aspect*100f).toInt().coerceIn(0,100),
                    regionConsistencyPercent=(region.similarity*100f).toInt().coerceIn(0,100),
                    stableRegionPercent=(region.stableRegionRatio*100f).toInt().coerceIn(0,100),
                    spatialDisagreementPercent=(region.disagreementPenalty*100f).toInt().coerceIn(0,100),
                    bestQueryVariant=variantLabel,
                    evidenceReasons=reasons
                )
            }.sortedByDescending { it.finalSimilarity }.take(limit)
        } finally { original.recycle() }
    }

    private fun buildQueryVariants(original: Bitmap): List<QueryVariant> {
        val result = ArrayList<QueryVariant>(7)
        fun add(label: String, bitmap: Bitmap) { result += QueryVariant(label, engine.fingerprint(bitmap), bitmap) }
        add("original", original)
        for (angle in intArrayOf(90, 180, 270)) {
            val matrix = Matrix().apply { postRotate(angle.toFloat()) }
            add("rotation_${angle}", Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true))
        }
        for (ratio in floatArrayOf(0.92f, 0.82f, 0.72f)) {
            val w = maxOf(1, (original.width * ratio).toInt()); val h = maxOf(1, (original.height * ratio).toInt())
            val left = (original.width - w) / 2; val top = (original.height - h) / 2
            add("center_crop_${(ratio*100).toInt()}", Bitmap.createBitmap(original, left, top, w, h))
        }
        return result
    }

    private fun AdvancedVisualFingerprintEntity.toFingerprint() = AdvancedVisualFingerprintEngine.Fingerprint(
        grayPyramid=grayPyramid, colorMoments=colorMoments, spatialColor=spatialColor,
        lbpHistogram=lbpHistogram, spatialLbp=spatialLbp, gradientHistogram=gradientHistogram,
        gradientMagnitude=gradientMagnitude, layoutSignature=layoutSignature,
        illuminationRobustStructure=illuminationRobustStructure, entropy=entropy, aspectRatio=aspectRatio
    )

    override fun close() = Unit
}
