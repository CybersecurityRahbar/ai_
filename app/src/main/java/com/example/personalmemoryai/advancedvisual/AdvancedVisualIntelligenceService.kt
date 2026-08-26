package com.example.personalmemoryai.advancedvisual

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.reverseimage.ReverseImageSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Independent Advanced search service. It consumes the shared corpus, unions candidate recall, then fuses evidence. */
class AdvancedVisualIntelligenceService(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val advancedDao = database.advancedVisualFingerprintDao()
    private val itemDao = database.reverseImageItemDao()
    private val engine = AdvancedVisualFingerprintEngine()

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
        val evidenceReasons: List<String>
    )

    suspend fun fingerprintCount(): Long = withContext(Dispatchers.IO) { advancedDao.count() }

    suspend fun search(
        queryUri: Uri,
        limit: Int = 50,
        minimumSimilarity: Float = 0.35f,
        onProgress: (processed: Int, total: Int, stage: String) -> Unit = { _, _, _ -> }
    ): List<Evidence> = withContext(Dispatchers.Default) {
        val queryBitmap = appContext.contentResolver.openInputStream(queryUri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "تعذر قراءة صورة البحث المتقدم: $queryUri" }
        }
        try {
            onProgress(0, 1, "تحضير Advanced Visual Intelligence V2")
            val query = engine.fingerprint(queryBitmap)
            val stored = withContext(Dispatchers.IO) {
                advancedDao.getAll(AdvancedVisualFingerprintEngine.ENGINE_VERSION)
            }
            if (stored.isEmpty()) return@withContext emptyList()

            val baseResults = runCatching {
                ReverseImageSearchService(appContext).use { service ->
                    service.search(queryUri, limit = 64, minimumSimilarity = 0f)
                }
            }.getOrDefault(emptyList())
            val baseById = baseResults.associateBy { it.item.id }

            val scored = ArrayList<Pair<AdvancedVisualFingerprintEntity, AdvancedVisualFingerprintEngine.Score>>(stored.size)
            for ((index, entity) in stored.withIndex()) {
                scored += entity to engine.compare(query, entity.toFingerprint())
                if (index % 16 == 0 || index == stored.lastIndex) {
                    onProgress(index + 1, stored.size, "Advanced V2 global analysis • ${index + 1}/${stored.size}")
                }
            }

            // Preserve recall from both engines: a strong classical candidate must not disappear merely
            // because its Advanced V2 global score is lower, and vice versa.
            val advancedTop = scored.sortedByDescending { it.second.similarity }.take(64)
            val advancedById = scored.associateBy { it.first.itemId }
            val candidateIds = LinkedHashSet<Long>().apply {
                baseResults.forEach { add(it.item.id) }
                advancedTop.forEach { add(it.first.itemId) }
            }
            val items = withContext(Dispatchers.IO) {
                itemDao.getByIds(candidateIds.toList()).associateBy { it.id }
            }

            return@withContext candidateIds.mapNotNull { itemId ->
                val item = items[itemId] ?: return@mapNotNull null
                val pair = advancedById[itemId] ?: return@mapNotNull null
                val score = pair.second
                val base = baseById[itemId]
                val baseScore = base?.similarity ?: 0f
                val agreementSignals = listOf(score.structure, score.spatialColor, score.spatialTexture, score.gradient, score.illumination)
                val consensus = agreementSignals.count { it >= 0.62f }
                var final = if (base != null) baseScore * 0.58f + score.similarity * 0.42f else score.similarity * 0.70f

                if (consensus <= 1 && final > 0.55f) final *= 0.78f
                if (score.structure < 0.50f && score.illumination < 0.45f && score.spatialColor > 0.80f) final *= 0.82f
                if (score.texture > 0.80f && score.structure < 0.45f) final *= 0.90f
                if (base != null && base.ransacInliers == 0 && score.structure < 0.50f) final *= 0.90f
                if (consensus >= 3 && score.gradient >= 0.65f && score.illumination >= 0.60f) final = (final + 0.025f).coerceAtMost(1f)
                final = final.coerceIn(0f, 1f)
                if (final < minimumSimilarity) return@mapNotNull null

                val reasons = buildList {
                    addAll(score.evidence)
                    if (base != null && base.percent >= 85) add("strong_existing_classical_agreement")
                    if (base != null && base.ransacInliers >= 4) add("existing_geometric_match")
                    if (score.spatialColor >= 0.78f) add("spatial_color_consistency")
                    if (score.spatialTexture >= 0.76f) add("regional_texture_consistency")
                    if (score.illumination >= 0.72f) add("illumination_robust_match")
                    if (consensus >= 3) add("independent_signal_consensus")
                    if (consensus <= 1) add("weak_cross_signal_consensus")
                    if (score.spatialColor >= 0.80f && score.structure < 0.50f) add("color_structure_contradiction")
                    if (score.texture >= 0.80f && score.structure < 0.45f) add("texture_structure_contradiction")
                    if (base == null) add("advanced_only_candidate")
                }.distinct()

                Evidence(
                    itemId=item.id,
                    displayName=item.displayName,
                    filePath=item.filePath,
                    finalSimilarity=final,
                    finalPercent=(final*100f).toInt().coerceIn(0,100),
                    baseClassicalPercent=base?.percent ?: 0,
                    haarPercent=base?.haarPercent ?: 0,
                    phashPercent=base?.phashPercent ?: 0,
                    dhashPercent=base?.dhashPercent ?: 0,
                    colorPercent=base?.colorPercent ?: 0,
                    edgePercent=base?.edgePercent ?: 0,
                    localPercent=base?.localPercent ?: 0,
                    ransacInliers=base?.ransacInliers ?: 0,
                    advancedPercent=(score.similarity*100f).toInt().coerceIn(0,100),
                    structurePercent=(score.structure*100f).toInt().coerceIn(0,100),
                    advancedColorPercent=(score.color*100f).toInt().coerceIn(0,100),
                    spatialColorPercent=(score.spatialColor*100f).toInt().coerceIn(0,100),
                    texturePercent=(score.texture*100f).toInt().coerceIn(0,100),
                    spatialTexturePercent=(score.spatialTexture*100f).toInt().coerceIn(0,100),
                    gradientPercent=(score.gradient*100f).toInt().coerceIn(0,100),
                    gradientMagnitudePercent=(score.gradient*100f).toInt().coerceIn(0,100),
                    layoutPercent=(score.layout*100f).toInt().coerceIn(0,100),
                    illuminationPercent=(score.illumination*100f).toInt().coerceIn(0,100),
                    entropyPercent=(score.entropy*100f).toInt().coerceIn(0,100),
                    aspectPercent=(score.aspect*100f).toInt().coerceIn(0,100),
                    evidenceReasons=reasons
                )
            }.sortedByDescending { it.finalSimilarity }.take(limit)
        } finally { queryBitmap.recycle() }
    }

    private fun AdvancedVisualFingerprintEntity.toFingerprint() = AdvancedVisualFingerprintEngine.Fingerprint(
        grayPyramid=grayPyramid,
        colorMoments=colorMoments,
        spatialColor=spatialColor,
        lbpHistogram=lbpHistogram,
        spatialLbp=spatialLbp,
        gradientHistogram=gradientHistogram,
        gradientMagnitude=gradientMagnitude,
        layoutSignature=layoutSignature,
        illuminationRobustStructure=illuminationRobustStructure,
        entropy=entropy,
        aspectRatio=aspectRatio
    )

    override fun close() = Unit
}
