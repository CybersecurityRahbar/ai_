package com.example.personalmemoryai.advancedvisual

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.reverseimage.ReverseImageSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val texturePercent: Int,
        val gradientPercent: Int,
        val layoutPercent: Int,
        val evidenceReasons: List<String>
    )

    suspend fun fingerprintCount(): Long = withContext(Dispatchers.IO) { advancedDao.count() }

    suspend fun search(queryUri: Uri, limit: Int = 50, minimumSimilarity: Float = 0.35f, onProgress: (processed: Int, total: Int, stage: String) -> Unit = { _, _, _ -> }): List<Evidence> = withContext(Dispatchers.Default) {
        val queryBitmap = appContext.contentResolver.openInputStream(queryUri).use { input -> requireNotNull(BitmapFactory.decodeStream(input)) { "تعذر قراءة صورة البحث المتقدم: $queryUri" } }
        try {
            onProgress(0, 1, "تحضير المحركات المتقدمة")
            val query = engine.fingerprint(queryBitmap)
            val stored = withContext(Dispatchers.IO) { advancedDao.getAll(AdvancedVisualFingerprintEngine.ENGINE_VERSION) }
            if (stored.isEmpty()) return@withContext emptyList()

            val baseResults = runCatching {
                ReverseImageSearchService(appContext).use { service -> service.search(queryUri, limit = 50, minimumSimilarity = 0f) }
            }.getOrDefault(emptyList())
            val baseById = baseResults.associateBy { it.item.id }

            val scored = ArrayList<Pair<AdvancedVisualFingerprintEntity, AdvancedVisualFingerprintEngine.Score>>(stored.size)
            for ((index, entity) in stored.withIndex()) {
                val score = engine.compare(query, entity.toFingerprint())
                scored += entity to score
                if (index % 16 == 0 || index == stored.lastIndex) onProgress(index + 1, stored.size, "تحليل Advanced Visual Intelligence")
            }

            val candidates = scored.sortedByDescending { it.second.similarity }.take(50)
            val ids = candidates.map { it.first.itemId }.toSet()
            val items = withContext(Dispatchers.IO) { itemDao.getByIds(ids.toList()).associateBy { it.id } }

            val results = candidates.mapNotNull { (entity, score) ->
                val item = items[entity.itemId] ?: return@mapNotNull null
                val base = baseById[entity.itemId]
                val final = if (base != null) (base.similarity * 0.65f + score.similarity * 0.35f).coerceIn(0f, 1f) else score.similarity * 0.55f
                val reasons = buildList {
                    addAll(score.evidence)
                    if (base != null && base.percent >= 85) add("strong_existing_classical_agreement")
                    if (base != null && base.ransacInliers >= 4) add("existing_geometric_match")
                    if (base == null) add("advanced_only_candidate")
                }.distinct()
                if (final < minimumSimilarity) return@mapNotNull null
                Evidence(
                    itemId = item.id,
                    displayName = item.displayName,
                    filePath = item.filePath,
                    finalSimilarity = final,
                    finalPercent = (final * 100f).toInt().coerceIn(0, 100),
                    baseClassicalPercent = base?.percent ?: 0,
                    haarPercent = base?.haarPercent ?: 0,
                    phashPercent = base?.phashPercent ?: 0,
                    dhashPercent = base?.dhashPercent ?: 0,
                    colorPercent = base?.colorPercent ?: 0,
                    edgePercent = base?.edgePercent ?: 0,
                    localPercent = base?.localPercent ?: 0,
                    ransacInliers = base?.ransacInliers ?: 0,
                    advancedPercent = (score.similarity * 100f).toInt().coerceIn(0, 100),
                    structurePercent = (score.structure * 100f).toInt().coerceIn(0, 100),
                    advancedColorPercent = (score.color * 100f).toInt().coerceIn(0, 100),
                    texturePercent = (score.texture * 100f).toInt().coerceIn(0, 100),
                    gradientPercent = (score.gradient * 100f).toInt().coerceIn(0, 100),
                    layoutPercent = (score.layout * 100f).toInt().coerceIn(0, 100),
                    evidenceReasons = reasons
                )
            }.sortedByDescending { it.finalSimilarity }

            results.take(limit)
        } finally { queryBitmap.recycle() }
    }

    private fun AdvancedVisualFingerprintEntity.toFingerprint(): AdvancedVisualFingerprintEngine.Fingerprint = AdvancedVisualFingerprintEngine.Fingerprint(
        grayPyramid = grayPyramid, colorMoments = colorMoments, lbpHistogram = lbpHistogram,
        gradientHistogram = gradientHistogram, layoutSignature = layoutSignature,
        entropy = entropy, aspectRatio = aspectRatio
    )

    override fun close() = Unit
}
