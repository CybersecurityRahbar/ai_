package com.example.personalmemoryai.semantic

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Local MobileCLIP-S2 semantic search with verified image/text towers. */
class SemanticSearchService(context: Context) : AutoCloseable {
    companion object {
        const val SEMANTIC_SPACE_VERSION = "mobileclip-s2-openclip-space-v1"
        const val MAX_RESULTS = 200
    }

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val modelManager = MobileClipModelManager(appContext)
    private val encoder = AppleMobileClipImageEncoder(appContext, modelManager)
    private val textModelManager = MobileClipTextModelManager(appContext)
    private val tokenizer = OpenClipTokenizer(appContext)
    private val textEncoder = MobileClipTextEncoder(textModelManager, tokenizer)
    private val diagnostics = DiagnosticsManager.get(appContext)

    suspend fun ensureModel() {
        if (!modelManager.isInstalled()) {
            diagnostics.record("VISUAL_ENGINE", "MODEL_CHECK", DiagnosticsManager.Severity.ERROR, "MobileCLIP-S2 image model is not installed")
            throw IllegalStateException("لم يتم استيراد نموذج MobileCLIP-S2 الصوري بعد")
        }
        try {
            check(encoder.load()) { "تعذر تحميل نموذج MobileCLIP-S2 الصوري" }
            diagnostics.record("VISUAL_ENGINE", "MODEL_READY", DiagnosticsManager.Severity.INFO, "MobileCLIP-S2 image runtime ready: ${encoder.tensorReport()}")
        } catch (t: Throwable) {
            diagnostics.record("VISUAL_ENGINE", "MODEL_LOAD", DiagnosticsManager.Severity.ERROR, "Failed to load MobileCLIP-S2 image tower", t)
            throw t
        }
    }

    suspend fun ensureTextModel() {
        if (!textModelManager.isInstalled()) {
            diagnostics.record("SEMANTIC_TEXT", "MODEL_CHECK", DiagnosticsManager.Severity.ERROR, "MobileCLIP-S2 text model is not installed")
            throw IllegalStateException("لم يتم استيراد نموذج Text TFLite الخاص بـ MobileCLIP-S2 بعد")
        }
        try {
            check(textEncoder.load()) { "تعذر تحميل نموذج Text TFLite" }
            diagnostics.record("SEMANTIC_TEXT", "MODEL_READY", DiagnosticsManager.Severity.INFO, "MobileCLIP-S2 text runtime ready; ${textEncoder.tokenizerContractReport()}")
        } catch (t: Throwable) {
            diagnostics.record("SEMANTIC_TEXT", "MODEL_LOAD", DiagnosticsManager.Severity.ERROR, "Failed to load MobileCLIP-S2 text tower", t)
            throw t
        }
    }

    suspend fun importModel(source: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) = importImageModel(source, onProgress)

    suspend fun importImageModel(source: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        modelManager.importModel(source, onProgress)
        encoder.close()
        diagnostics.record("VISUAL_ENGINE", "MODEL_IMPORTED", DiagnosticsManager.Severity.INFO, "MobileCLIP-S2 image tower imported")
    }

    suspend fun importTextModel(source: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        textModelManager.importModel(source, onProgress)
        textEncoder.close()
        diagnostics.record("SEMANTIC_TEXT", "MODEL_IMPORTED", DiagnosticsManager.Severity.INFO, "MobileCLIP-S2 text tower imported")
    }

    fun isModelInstalled(): Boolean = modelManager.isInstalled()
    fun modelSizeBytes(): Long = modelManager.installedSizeBytes()
    fun isTextModelInstalled(): Boolean = textModelManager.isInstalled()
    fun textModelSizeBytes(): Long = textModelManager.installedSizeBytes()

    suspend fun imageEmbeddingCount(): Long = withContext(Dispatchers.IO) {
        database.embeddingDao().countByOwnerType(AppleMobileClipImageEncoder.OWNER_TYPE)
    }

    suspend fun visualHealth(): VisualHealth = withContext(Dispatchers.IO) {
        val images = database.imageDao().count()
        val embeddings = database.embeddingDao().countByOwnerType(AppleMobileClipImageEncoder.OWNER_TYPE)
        val modelReady = try { ensureModel(); true } catch (_: Throwable) { false }
        val textReady = try { ensureTextModel(); true } catch (_: Throwable) { false }
        val dimension = if (modelReady) runCatching { encoder.modelOutputShape().fold(1) { a, b -> a * b } }.getOrDefault(-1) else -1
        val compatible = if (modelReady) {
            database.embeddingDao().getAllForImageSearch().count {
                it.modelName == AppleMobileClipImageEncoder.MODEL_NAME &&
                    it.modelVersion == AppleMobileClipImageEncoder.MODEL_VERSION &&
                    it.dimension == dimension && it.vector.size == dimension && it.vector.all(Float::isFinite)
            }
        } else 0
        VisualHealth(
            images.toLong(), embeddings, compatible, modelReady, textReady,
            modelManager.installedSizeBytes(), textModelManager.installedSizeBytes(),
            if (modelReady) encoder.modelInputShape() else intArrayOf(),
            if (modelReady) encoder.modelOutputShape() else intArrayOf()
        )
    }

    suspend fun indexAllImages(
        onProgress: (processed: Int, total: Int, embedded: Int, skipped: Int, failed: Int) -> Unit = { _, _, _, _, _ -> }
    ) = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("VISUAL_INDEX")
        try {
            ensureModel()
            val images = database.imageDao().getAll()
            val total = images.size
            run.stage("LOAD", "Loaded images for visual embedding", mapOf("total" to total.toString(), "tensor" to encoder.modelInputShape().contentToString()))
            var processed = 0
            var embedded = 0
            var skipped = 0
            var failed = 0
            for (image in images) {
                try {
                    val existing = database.embeddingDao().getForOwnerAndModel(AppleMobileClipImageEncoder.OWNER_TYPE, image.id, AppleMobileClipImageEncoder.MODEL_NAME, AppleMobileClipImageEncoder.MODEL_VERSION)
                    if (existing != null && existing.vector.isNotEmpty() && existing.dimension == existing.vector.size && existing.vector.all(Float::isFinite)) {
                        skipped++
                    } else {
                        database.embeddingDao().deleteForOwner(AppleMobileClipImageEncoder.OWNER_TYPE, image.id)
                        indexImageAndStore(image)
                        embedded++
                    }
                } catch (t: Throwable) {
                    failed++
                    run.failure("IMAGE_${image.id}", t)
                } finally {
                    processed++
                    onProgress(processed, total, embedded, skipped, failed)
                }
            }
            val persisted = database.embeddingDao().countByOwnerType(AppleMobileClipImageEncoder.OWNER_TYPE)
            run.stage("VERIFY", "Visual index persistence verification", mapOf("persisted" to persisted.toString()))
            if (total > 0 && persisted == 0L) throw IllegalStateException("تم تشغيل النموذج لكن لم يتم حفظ أي بصمة بصرية. راجع Diagnostics.")
            if (failed > 0) run.warning("Visual index completed with failed images", mapOf("failed" to failed.toString()))
            run.success("Visual index completed", mapOf("processed" to processed.toString(), "embedded" to embedded.toString(), "skipped" to skipped.toString(), "failed" to failed.toString(), "persisted" to persisted.toString()))
        } catch (t: Throwable) {
            run.failure("PIPELINE", t)
            throw t
        }
    }

    suspend fun indexAllImages(onProgress: (processed: Int, total: Int, embedded: Int, skipped: Int) -> Unit) =
        indexAllImages { processed, total, embedded, skipped, _ -> onProgress(processed, total, embedded, skipped) }

    suspend fun indexImage(image: ImageEntity): EmbeddingEntity = withContext(Dispatchers.Default) {
        ensureModel()
        val vector = encoder.encode(Uri.parse(image.uri))
        require(vector.size == AppleMobileClipImageEncoder.EMBEDDING_DIMENSION) { "MobileCLIP produced ${vector.size}-D output; expected ${AppleMobileClipImageEncoder.EMBEDDING_DIMENSION}" }
        require(vector.all(Float::isFinite)) { "MobileCLIP produced a non-finite embedding" }
        EmbeddingEntity(
            ownerType = AppleMobileClipImageEncoder.OWNER_TYPE,
            ownerId = image.id,
            vector = vector,
            dimension = vector.size,
            modelName = AppleMobileClipImageEncoder.MODEL_NAME,
            modelVersion = AppleMobileClipImageEncoder.MODEL_VERSION,
            normalized = true
        )
    }

    suspend fun indexImageAndStore(image: ImageEntity): Long {
        val embedding = indexImage(image)
        database.embeddingDao().deleteForOwner(embedding.ownerType, embedding.ownerId)
        return database.embeddingDao().insert(embedding)
    }

    suspend fun searchSimilarImages(queryUri: Uri, limit: Int = 30): List<ScoredImage> = withContext(Dispatchers.Default) {
        require(limit in 1..MAX_RESULTS) { "عدد النتائج يجب أن يكون بين 1 و$MAX_RESULTS" }
        val run = diagnostics.begin("VISUAL_SEARCH", mapOf("limit" to limit.toString()))
        try {
            ensureModel()
            val query = encoder.encode(queryUri)
            require(query.size == AppleMobileClipImageEncoder.EMBEDDING_DIMENSION) { "Invalid query embedding dimension: ${query.size}" }
            val embeddings = database.embeddingDao().getAllForImageSearch()
            if (embeddings.isEmpty()) throw IllegalStateException("لا توجد بصمات بصرية. اضغط BUILD VISUAL INDEX أولًا.")
            val images = database.imageDao().getByIds(embeddings.map { it.ownerId }.distinct()).associateBy { it.id }
            val results = ArrayList<ScoredImage>(minOf(embeddings.size, MAX_RESULTS))
            var compatible = 0
            for (embedding in embeddings) {
                if (embedding.modelName != AppleMobileClipImageEncoder.MODEL_NAME || embedding.modelVersion != AppleMobileClipImageEncoder.MODEL_VERSION || embedding.dimension != query.size || embedding.vector.size != embedding.dimension || !embedding.vector.all(Float::isFinite)) continue
                val image = images[embedding.ownerId] ?: continue
                compatible++
                val score = cosine(query, embedding.vector)
                results += ScoredImage(image, score, scoreBand(score), scorePercent(score))
            }
            if (compatible == 0) throw IllegalStateException("البصمات الموجودة غير متوافقة مع إصدار MobileCLIP-S2 الحالي. أعد بناء الفهرس البصري.")
            val ranked = results.sortedByDescending { it.score }.take(limit)
            run.success("Visual search completed", mapOf("compatible" to compatible.toString(), "results" to ranked.size.toString()))
            ranked
        } catch (t: Throwable) {
            run.failure("SEARCH", t)
            throw t
        }
    }

    suspend fun searchByText(queryText: String, limit: Int = 30): List<ScoredImage> = withContext(Dispatchers.Default) {
        require(limit in 1..MAX_RESULTS) { "عدد النتائج يجب أن يكون بين 1 و$MAX_RESULTS" }
        val normalized = queryText.trim()
        require(normalized.isNotEmpty()) { "اكتب وصفًا للبحث الدلالي" }
        val run = diagnostics.begin("SEMANTIC_TEXT_SEARCH", mapOf("limit" to limit.toString(), "queryLength" to normalized.length.toString()))
        try {
            ensureTextModel()
            val query = textEncoder.encode(normalized)
            require(query.size == MobileClipTextEncoder.EMBEDDING_DIMENSION && query.all(Float::isFinite)) { "تعذر إنشاء البصمة النصية الدلالية" }
            val embeddings = database.embeddingDao().getAllForImageSearch()
            if (embeddings.isEmpty()) throw IllegalStateException("لا توجد بصمات صور دلالية. اضغط BUILD VISUAL INDEX أولًا.")
            val images = database.imageDao().getByIds(embeddings.map { it.ownerId }.distinct()).associateBy { it.id }
            val results = ArrayList<ScoredImage>(minOf(embeddings.size, MAX_RESULTS))
            var compatible = 0
            for (embedding in embeddings) {
                if (embedding.modelName != AppleMobileClipImageEncoder.MODEL_NAME || embedding.modelVersion != AppleMobileClipImageEncoder.MODEL_VERSION || embedding.dimension != MobileClipTextEncoder.EMBEDDING_DIMENSION || embedding.vector.size != MobileClipTextEncoder.EMBEDDING_DIMENSION || !embedding.vector.all(Float::isFinite)) continue
                val image = images[embedding.ownerId] ?: continue
                compatible++
                val score = cosine(query, embedding.vector)
                results += ScoredImage(image, score, scoreBand(score), scorePercent(score))
            }
            if (compatible == 0) throw IllegalStateException("لا توجد بصمات صور متوافقة مع مساحة MobileCLIP-S2. أعد بناء الفهرس البصري.")
            val ranked = results.sortedByDescending { it.score }.take(limit)
            run.success("Text semantic search completed", mapOf("compatible" to compatible.toString(), "results" to ranked.size.toString(), "spaceVersion" to SEMANTIC_SPACE_VERSION))
            ranked
        } catch (t: Throwable) {
            run.failure("SEARCH", t)
            throw t
        }
    }

    fun textTokenIds(text: String): LongArray = textEncoder.tokenIds(text)

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var normA = 0.0; var normB = 0.0
        require(a.size == b.size) { "Embedding dimensions differ: ${a.size} vs ${b.size}" }
        for (i in a.indices) { dot += a[i].toDouble() * b[i].toDouble(); normA += a[i].toDouble() * a[i].toDouble(); normB += b[i].toDouble() * b[i].toDouble() }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(-1f, 1f)
    }

    private fun scorePercent(score: Float): Int = (((score + 1f) / 2f) * 100f).coerceIn(0f, 100f).toInt()
    private fun scoreBand(score: Float): MatchBand = when { score >= 0.92f -> MatchBand.VERY_HIGH; score >= 0.82f -> MatchBand.HIGH; score >= 0.68f -> MatchBand.MEDIUM; score >= 0.50f -> MatchBand.LOW; else -> MatchBand.VERY_LOW }

    enum class MatchBand { VERY_HIGH, HIGH, MEDIUM, LOW, VERY_LOW }
    data class ScoredImage(val image: ImageEntity, val score: Float, val band: MatchBand, val percent: Int)
    data class VisualHealth(val images: Long, val embeddings: Long, val compatibleEmbeddings: Int, val modelReady: Boolean, val textModelReady: Boolean, val modelBytes: Long, val textModelBytes: Long, val inputShape: IntArray, val outputShape: IntArray)

    override fun close() { encoder.close(); textEncoder.close() }
}
