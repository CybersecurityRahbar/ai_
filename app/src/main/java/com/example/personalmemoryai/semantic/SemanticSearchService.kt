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

/**
 * Local MobileCLIP-S2 semantic search.
 *
 * The image tower builds the persistent IMAGE embedding index. The text tower
 * encodes a query into the same 512-D semantic space and ranks those stored
 * image embeddings locally. No network access is required at search time.
 */
class SemanticSearchService(context: Context) : AutoCloseable {
    companion object {
        const val SEMANTIC_SPACE_VERSION = "mobileclip-s2-openclip-space-v1"
    }

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val modelManager = MobileClipModelManager(appContext)
    private val encoder = MobileClipImageEncoder(appContext, modelManager)
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
            diagnostics.record(
                "VISUAL_ENGINE",
                "MODEL_READY",
                DiagnosticsManager.Severity.INFO,
                "MobileCLIP-S2 image runtime ready: ${encoder.tensorReport()}"
            )
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
            diagnostics.record(
                "SEMANTIC_TEXT",
                "MODEL_READY",
                DiagnosticsManager.Severity.INFO,
                "MobileCLIP-S2 text runtime ready; ${textEncoder.tokenizerContractReport()}"
            )
        } catch (t: Throwable) {
            diagnostics.record("SEMANTIC_TEXT", "MODEL_LOAD", DiagnosticsManager.Severity.ERROR, "Failed to load MobileCLIP-S2 text tower", t)
            throw t
        }
    }

    /** Compatibility API retained for the existing image-model UI. */
    suspend fun importModel(source: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        importImageModel(source, onProgress)
    }

    suspend fun importImageModel(source: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        modelManager.importModel(source, onProgress)
        encoder.close()
        diagnostics.record(
            "VISUAL_ENGINE",
            "MODEL_IMPORTED",
            DiagnosticsManager.Severity.INFO,
            "MobileCLIP-S2 image tower imported; runtime will reload on next operation"
        )
    }

    suspend fun importTextModel(source: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        textModelManager.importModel(source, onProgress)
        textEncoder.close()
        diagnostics.record(
            "SEMANTIC_TEXT",
            "MODEL_IMPORTED",
            DiagnosticsManager.Severity.INFO,
            "MobileCLIP-S2 text tower imported; runtime will reload on next operation"
        )
    }

    fun isModelInstalled(): Boolean = modelManager.isInstalled()
    fun modelSizeBytes(): Long = modelManager.installedSizeBytes()
    fun isTextModelInstalled(): Boolean = textModelManager.isInstalled()
    fun textModelSizeBytes(): Long = textModelManager.installedSizeBytes()

    suspend fun imageEmbeddingCount(): Long = withContext(Dispatchers.IO) {
        database.embeddingDao().countByOwnerType(MobileClipImageEncoder.OWNER_TYPE)
    }

    suspend fun visualHealth(): VisualHealth = withContext(Dispatchers.IO) {
        val images = database.imageDao().count()
        val embeddings = database.embeddingDao().countByOwnerType(MobileClipImageEncoder.OWNER_TYPE)
        val modelReady = try { ensureModel(); true } catch (_: Throwable) { false }
        val textReady = try { ensureTextModel(); true } catch (_: Throwable) { false }
        val dimension = if (modelReady) {
            runCatching { encoder.modelOutputShape().fold(1) { a, b -> a * b } }.getOrDefault(-1)
        } else -1
        val compatible = if (modelReady) {
            database.embeddingDao().getAllForImageSearch().count {
                it.modelName == MobileClipImageEncoder.MODEL_NAME &&
                    it.modelVersion == MobileClipImageEncoder.MODEL_VERSION &&
                    it.dimension == dimension &&
                    it.vector.size == dimension &&
                    it.vector.all(Float::isFinite)
            }
        } else 0
        VisualHealth(
            images = images.toLong(),
            embeddings = embeddings,
            compatibleEmbeddings = compatible,
            modelReady = modelReady,
            textModelReady = textReady,
            modelBytes = modelManager.installedSizeBytes(),
            textModelBytes = textModelManager.installedSizeBytes(),
            inputShape = if (modelReady) encoder.modelInputShape() else intArrayOf(),
            outputShape = if (modelReady) encoder.modelOutputShape() else intArrayOf()
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
                    val existing = database.embeddingDao().getForOwnerAndModel(
                        MobileClipImageEncoder.OWNER_TYPE,
                        image.id,
                        MobileClipImageEncoder.MODEL_NAME,
                        MobileClipImageEncoder.MODEL_VERSION
                    )
                    if (
                        existing != null &&
                        existing.vector.isNotEmpty() &&
                        existing.dimension == existing.vector.size &&
                        existing.vector.all(Float::isFinite)
                    ) {
                        skipped++
                    } else {
                        database.embeddingDao().deleteForOwner(MobileClipImageEncoder.OWNER_TYPE, image.id)
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
            val persisted = database.embeddingDao().countByOwnerType(MobileClipImageEncoder.OWNER_TYPE)
            run.stage(
                "VERIFY",
                "Visual index persistence verification",
                mapOf("persisted" to persisted.toString(), "expectedMinimum" to (total - failed).coerceAtLeast(0).toString())
            )
            if (total > 0 && persisted == 0L) {
                val error = IllegalStateException("No MobileCLIP embeddings were persisted")
                run.failure("ZERO_PERSISTED_EMBEDDINGS", error)
                throw IllegalStateException("تم تشغيل النموذج لكن لم يتم حفظ أي بصمة بصرية. راجع Diagnostics.")
            }
            if (failed > 0) {
                run.warning("Visual index completed with failed images", mapOf("failed" to failed.toString()))
            }
            run.success(
                "Visual index completed",
                mapOf(
                    "processed" to processed.toString(),
                    "embedded" to embedded.toString(),
                    "skipped" to skipped.toString(),
                    "failed" to failed.toString(),
                    "persisted" to persisted.toString()
                )
            )
        } catch (t: Throwable) {
            run.failure("PIPELINE", t)
            throw t
        }
    }

    /** Compatibility overload for the legacy MainActivity callback. */
    suspend fun indexAllImages(onProgress: (processed: Int, total: Int, embedded: Int, skipped: Int) -> Unit) =
        indexAllImages { processed, total, embedded, skipped, _ -> onProgress(processed, total, embedded, skipped) }

    suspend fun indexImage(image: ImageEntity): EmbeddingEntity = withContext(Dispatchers.Default) {
        ensureModel()
        val vector = encoder.encode(Uri.parse(image.uri))
        require(vector.isNotEmpty() && vector.all(Float::isFinite)) { "MobileCLIP produced an invalid embedding" }
        EmbeddingEntity(
            ownerType = MobileClipImageEncoder.OWNER_TYPE,
            ownerId = image.id,
            vector = vector,
            dimension = vector.size,
            modelName = MobileClipImageEncoder.MODEL_NAME,
            modelVersion = MobileClipImageEncoder.MODEL_VERSION,
            normalized = true
        )
    }

    suspend fun indexImageAndStore(image: ImageEntity): Long {
        val embedding = indexImage(image)
        database.embeddingDao().deleteForOwner(embedding.ownerType, embedding.ownerId)
        return database.embeddingDao().insert(embedding)
    }

    suspend fun searchSimilarImages(queryUri: Uri, limit: Int = 30): List<ScoredImage> = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("VISUAL_SEARCH", mapOf("limit" to limit.toString()))
        try {
            ensureModel()
            val query = encoder.encode(queryUri)
            require(query.isNotEmpty() && query.all(Float::isFinite)) { "تعذر إنشاء البصمة البصرية لصورة البحث" }
            val embeddings = database.embeddingDao().getAllForImageSearch()
            run.stage("LOAD_INDEX", "Loaded visual embeddings", mapOf("embeddings" to embeddings.size.toString(), "queryDimension" to query.size.toString()))
            if (embeddings.isEmpty()) {
                run.warning("VISUAL_INDEX_EMPTY", mapOf("message" to "Build the visual index before image search"))
                throw IllegalStateException("لا توجد بصمات بصرية. اضغط BUILD VISUAL INDEX أولًا.")
            }
            val imageIds = embeddings.map { it.ownerId }.distinct()
            val images = database.imageDao().getByIds(imageIds).associateBy { it.id }
            val results = ArrayList<ScoredImage>(embeddings.size)
            var compatible = 0
            for (embedding in embeddings) {
                if (
                    embedding.modelName != MobileClipImageEncoder.MODEL_NAME ||
                    embedding.modelVersion != MobileClipImageEncoder.MODEL_VERSION ||
                    embedding.dimension != query.size ||
                    embedding.vector.size != embedding.dimension ||
                    !embedding.vector.all(Float::isFinite)
                ) continue
                val image = images[embedding.ownerId] ?: continue
                compatible++
                val score = cosine(query, embedding.vector)
                results += ScoredImage(image, score, scoreBand(score), scorePercent(score))
            }
            if (compatible == 0) {
                val error = IllegalStateException("Stored visual embeddings are incompatible with the active MobileCLIP model/version")
                run.failure("NO_COMPATIBLE_EMBEDDINGS", error)
                throw IllegalStateException("البصمات الموجودة غير متوافقة مع إصدار MobileCLIP-S2 الحالي. أعد بناء الفهرس البصري.")
            }
            val ranked = results.sortedByDescending { it.score }.take(limit)
            run.success("Visual search completed", mapOf("compatible" to compatible.toString(), "results" to ranked.size.toString()))
            ranked
        } catch (t: Throwable) {
            run.failure("SEARCH", t)
            throw t
        }
    }

    /**
     * Text-to-image semantic retrieval. Query text is embedded by the verified
     * MobileCLIP-S2 text tower and compared directly with persisted image
     * embeddings from the same semantic space.
     */
    suspend fun searchByText(queryText: String, limit: Int = 30): List<ScoredImage> = withContext(Dispatchers.Default) {
        val normalized = queryText.trim()
        require(normalized.isNotEmpty()) { "اكتب وصفًا للبحث الدلالي" }
        val run = diagnostics.begin("SEMANTIC_TEXT_SEARCH", mapOf("limit" to limit.toString(), "queryLength" to normalized.length.toString()))
        try {
            ensureTextModel()
            val query = textEncoder.encode(normalized)
            require(query.size == MobileClipTextEncoder.EMBEDDING_DIMENSION && query.all(Float::isFinite)) {
                "تعذر إنشاء البصمة النصية الدلالية"
            }
            val embeddings = database.embeddingDao().getAllForImageSearch()
            if (embeddings.isEmpty()) {
                run.warning("SEMANTIC_INDEX_EMPTY", mapOf("message" to "Build the visual index before text search"))
                throw IllegalStateException("لا توجد بصمات صور دلالية. اضغط BUILD VISUAL INDEX أولًا.")
            }
            val imageIds = embeddings.map { it.ownerId }.distinct()
            val images = database.imageDao().getByIds(imageIds).associateBy { it.id }
            val results = ArrayList<ScoredImage>(embeddings.size)
            var compatible = 0
            for (embedding in embeddings) {
                if (
                    embedding.modelName != MobileClipImageEncoder.MODEL_NAME ||
                    embedding.modelVersion != MobileClipImageEncoder.MODEL_VERSION ||
                    embedding.dimension != MobileClipTextEncoder.EMBEDDING_DIMENSION ||
                    embedding.vector.size != MobileClipTextEncoder.EMBEDDING_DIMENSION ||
                    !embedding.vector.all(Float::isFinite)
                ) continue
                val image = images[embedding.ownerId] ?: continue
                compatible++
                val score = cosine(query, embedding.vector)
                results += ScoredImage(image, score, scoreBand(score), scorePercent(score))
            }
            if (compatible == 0) {
                val error = IllegalStateException("No compatible 512-D MobileCLIP image embeddings are indexed")
                run.failure("NO_COMPATIBLE_IMAGE_EMBEDDINGS", error)
                throw IllegalStateException("لا توجد بصمات صور متوافقة مع مساحة MobileCLIP-S2. أعد بناء الفهرس البصري.")
            }
            val ranked = results.sortedByDescending { it.score }.take(limit)
            run.success(
                "Text semantic search completed",
                mapOf(
                    "compatible" to compatible.toString(),
                    "results" to ranked.size.toString(),
                    "spaceVersion" to SEMANTIC_SPACE_VERSION
                )
            )
            ranked
        } catch (t: Throwable) {
            run.failure("SEARCH", t)
            throw t
        }
    }

    fun textTokenIds(text: String): LongArray = textEncoder.tokenIds(text)

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        require(a.size == b.size) { "Embedding dimensions differ: ${a.size} vs ${b.size}" }
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(-1f, 1f)
    }

    private fun scorePercent(score: Float): Int = (((score + 1f) / 2f) * 100f).coerceIn(0f, 100f).toInt()

    private fun scoreBand(score: Float): MatchBand = when {
        score >= 0.92f -> MatchBand.VERY_HIGH
        score >= 0.82f -> MatchBand.HIGH
        score >= 0.68f -> MatchBand.MEDIUM
        score >= 0.50f -> MatchBand.LOW
        else -> MatchBand.VERY_LOW
    }

    enum class MatchBand { VERY_HIGH, HIGH, MEDIUM, LOW, VERY_LOW }

    data class ScoredImage(
        val image: ImageEntity,
        val score: Float,
        val band: MatchBand,
        val percent: Int
    )

    data class VisualHealth(
        val images: Long,
        val embeddings: Long,
        val compatibleEmbeddings: Int,
        val modelReady: Boolean,
        val textModelReady: Boolean,
        val modelBytes: Long,
        val textModelBytes: Long,
        val inputShape: IntArray,
        val outputShape: IntArray
    )

    override fun close() {
        encoder.close()
        textEncoder.close()
    }
}
