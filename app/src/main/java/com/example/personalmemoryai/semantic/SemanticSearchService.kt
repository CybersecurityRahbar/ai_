package com.example.personalmemoryai.semantic

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.ImageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Image-to-image semantic retrieval.
 *
 * Until the compatible MobileCLIP-S2 Text Encoder is installed, the query
 * side is another image. The same 512-D image space is used for both indexed
 * images and query images, so cosine similarity is a valid retrieval metric.
 */
class SemanticSearchService(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val modelManager = MobileClipModelManager(appContext)
    private val encoder = MobileClipImageEncoder(appContext, modelManager)

    suspend fun ensureModel(
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        modelManager.ensureModel(onProgress)
        encoder.load()
    }

    fun isModelInstalled(): Boolean = modelManager.isInstalled()

    suspend fun indexImage(image: ImageEntity): EmbeddingEntity = withContext(Dispatchers.Default) {
        val vector = encoder.encode(Uri.parse(image.uri))
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
        database.embeddingDao().deleteForOwner(
            embedding.ownerType,
            embedding.ownerId
        )
        return database.embeddingDao().insert(embedding)
    }

    suspend fun searchSimilarImages(
        queryUri: Uri,
        limit: Int = 30
    ): List<ScoredImage> = withContext(Dispatchers.Default) {
        val query = encoder.encode(queryUri)
        val embeddings = database.embeddingDao().getAllForImageSearch()
        val results = ArrayList<ScoredImage>(embeddings.size)

        for (embedding in embeddings) {
            if (embedding.dimension != query.size) continue
            val score = cosine(query, embedding.vector)
            val image = database.imageDao().getById(embedding.ownerId) ?: continue
            results += ScoredImage(image, score)
        }

        results.sortedByDescending { it.score }.take(limit)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))).toFloat()
    }

    data class ScoredImage(
        val image: ImageEntity,
        val score: Float
    )

    override fun close() {
        encoder.close()
    }
}
