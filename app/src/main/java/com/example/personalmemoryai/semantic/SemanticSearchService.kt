package com.example.personalmemoryai.semantic

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local image-to-image retrieval using imported MobileCLIP-S2 with explicit diagnostics. */
class SemanticSearchService(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val modelManager = MobileClipModelManager(appContext)
    private val encoder = MobileClipImageEncoder(appContext, modelManager)
    private val diagnostics = DiagnosticsManager.get(appContext)

    suspend fun ensureModel() {
        if (!modelManager.isInstalled()) {
            diagnostics.record("VISUAL_ENGINE", "MODEL_CHECK", DiagnosticsManager.Severity.ERROR, "MobileCLIP-S2 is not installed")
            throw IllegalStateException("لم يتم استيراد نموذج MobileCLIP-S2 بعد")
        }
        try {
            check(encoder.load()) { "تعذر تحميل نموذج MobileCLIP-S2" }
        } catch (t: Throwable) {
            diagnostics.record("VISUAL_ENGINE", "MODEL_LOAD", DiagnosticsManager.Severity.ERROR, "Failed to load MobileCLIP-S2", t)
            throw t
        }
    }

    suspend fun importModel(source: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        modelManager.importModel(source, onProgress)
        encoder.close()
    }

    fun isModelInstalled(): Boolean = modelManager.isInstalled()
    fun modelSizeBytes(): Long = modelManager.installedSizeBytes()

    suspend fun imageEmbeddingCount(): Long = withContext(Dispatchers.IO) {
        database.embeddingDao().countByOwnerType(MobileClipImageEncoder.OWNER_TYPE)
    }

    suspend fun indexAllImages(
        onProgress: (processed: Int, total: Int, embedded: Int, skipped: Int) -> Unit = { _, _, _, _ -> }
    ) = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("VISUAL_INDEX")
        try {
            ensureModel()
            val images = database.imageDao().getAll()
            val total = images.size
            run.stage("LOAD", "Loaded images for visual embedding", mapOf("total" to total.toString()))

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
                    if (existing != null && existing.vector.isNotEmpty() && existing.dimension == existing.vector.size) {
                        skipped++
                    } else {
                        // Remove stale/incompatible data before writing the new embedding.
                        database.embeddingDao().deleteForOwner(MobileClipImageEncoder.OWNER_TYPE, image.id)
                        indexImageAndStore(image)
                        embedded++
                    }
                } catch (t: Throwable) {
                    failed++
                    run.failure("IMAGE_${image.id}", t)
                } finally {
                    processed++
                    onProgress(processed, total, embedded, skipped)
                }
            }

            run.stage(
                "VERIFY",
                "Verifying persisted visual embeddings",
                mapOf("persisted" to database.embeddingDao().countByOwnerType(MobileClipImageEncoder.OWNER_TYPE).toString())
            )

            val persisted = database.embeddingDao().countByOwnerType(MobileClipImageEncoder.OWNER_TYPE)
            if (total > 0 && persisted == 0L) {
                run.failure(
                    "ZERO_PERSISTED_EMBEDDINGS",
                    IllegalStateException("No MobileCLIP embeddings were persisted; visual search cannot be enabled")
                )
                throw IllegalStateException("تم تحليل الصور لكن لم يتم حفظ أي بصمة بصرية. افتح Diagnostics لمعرفة سبب فشل كل صورة.")
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

    suspend fun indexImage(image: ImageEntity): EmbeddingEntity = withContext(Dispatchers.Default) {
        ensureModel()
        val vector = encoder.encode(Uri.parse(image.uri))
        require(vector.isNotEmpty()) { "MobileCLIP produced an empty embedding" }
        require(vector.all { it.isFinite() }) { "MobileCLIP produced non-finite embedding values" }
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
            require(query.isNotEmpty() && query.all { it.isFinite() }) { "تعذر إنشاء البصمة البصرية لصورة البحث" }

            val embeddings = database.embeddingDao().getAllForImageSearch()
            run.stage(
                "LOAD_INDEX",
                "Loaded visual embeddings",
                mapOf("embeddings" to embeddings.size.toString(), "queryDimension" to query.size.toString())
            )

            if (embeddings.isEmpty()) {
                run.warning("VISUAL_INDEX_EMPTY", mapOf("message" to "Build the visual index before image search"))
                throw IllegalStateException("لا توجد بصمات بصرية. اضغط BUILD VISUAL INDEX أولًا.")
            }

            val results = ArrayList<ScoredImage>(embeddings.size)
            val imageIds = embeddings.map { it.ownerId }.distinct()
            val images = database.imageDao().getByIds(imageIds).associateBy { it.id }
            var compatible = 0

            for (embedding in embeddings) {
                if (
                    embedding.modelName != MobileClipImageEncoder.MODEL_NAME ||
                    embedding.modelVersion != MobileClipImageEncoder.MODEL_VERSION ||
                    embedding.dimension != query.size ||
                    embedding.vector.size != embedding.dimension ||
                    !embedding.vector.all { it.isFinite() }
                ) continue

                compatible++
                val image = images[embedding.ownerId] ?: continue
                results += ScoredImage(image, cosine(query, embedding.vector))
            }

            if (compatible == 0) {
                run.failure(
                    "NO_COMPATIBLE_EMBEDDINGS",
                    IllegalStateException("Stored visual embeddings are incompatible with the active MobileCLIP model/version")
                )
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

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        val size = minOf(a.size, b.size)
        for (i in 0 until size) {
            dot += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))).toFloat()
    }

    data class ScoredImage(val image: ImageEntity, val score: Float)

    override fun close() = encoder.close()
}
