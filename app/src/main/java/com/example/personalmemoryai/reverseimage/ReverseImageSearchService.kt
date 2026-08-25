package com.example.personalmemoryai.reverseimage

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Standalone local reverse-image search. It never consults MobileCLIP or the
 * normal text/semantic search path.
 */
class ReverseImageSearchService(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val fingerprintDao = database.haarFingerprintDao()
    private val imageDao = database.imageDao()
    private val engine = HaarFingerprintEngine()
    private val diagnostics = DiagnosticsManager.get(appContext)

    data class IndexProgress(
        val processed: Int,
        val total: Int,
        val indexed: Int,
        val skipped: Int,
        val failed: Int
    )

    data class Result(
        val image: ImageEntity,
        val similarity: Float,
        val percent: Int,
        val matchedCoefficients: Int
    )

    suspend fun fingerprintCount(): Long = withContext(Dispatchers.IO) { fingerprintDao.count() }

    suspend fun buildIndex(
        rebuild: Boolean = false,
        onProgress: (IndexProgress) -> Unit = {}
    ): IndexProgress = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("REVERSE_IMAGE_INDEX", mapOf("rebuild" to rebuild.toString()))
        val images = withContext(Dispatchers.IO) { imageDao.getAll() }
        var indexed = 0
        var skipped = 0
        var failed = 0
        var processed = 0

        for (image in images) {
            try {
                val existing = withContext(Dispatchers.IO) { fingerprintDao.getForImage(image.id) }
                val unchanged = existing != null &&
                    existing.engineVersion == HaarFingerprintEngine.ENGINE_VERSION &&
                    existing.sourceModifiedAt == image.dateModified
                if (!rebuild && unchanged) {
                    skipped++
                } else {
                    val fingerprint = decodeAndFingerprint(Uri.parse(image.uri))
                    val entity = HaarFingerprintEntity(
                        imageId = image.id,
                        engineVersion = HaarFingerprintEngine.ENGINE_VERSION,
                        sourceModifiedAt = image.dateModified,
                        width = fingerprint.width,
                        height = fingerprint.height,
                        channels = fingerprint.channels,
                        signature = fingerprint.signature
                    )
                    withContext(Dispatchers.IO) { fingerprintDao.insert(entity) }
                    indexed++
                }
            } catch (t: Throwable) {
                failed++
                run.failure("IMAGE_${image.id}", t)
            } finally {
                processed++
                onProgress(IndexProgress(processed, images.size, indexed, skipped, failed))
            }
        }

        run.success("Reverse-image fingerprint index completed", mapOf(
            "processed" to processed.toString(),
            "indexed" to indexed.toString(),
            "skipped" to skipped.toString(),
            "failed" to failed.toString(),
            "stored" to fingerprintDao.count().toString()
        ))
        IndexProgress(processed, images.size, indexed, skipped, failed)
    }

    suspend fun search(
        queryUri: Uri,
        limit: Int = 50,
        minimumSimilarity: Float = 0f
    ): List<Result> = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("REVERSE_IMAGE_SEARCH", mapOf("limit" to limit.toString(), "minimum" to minimumSimilarity.toString()))
        val query = decodeAndFingerprint(queryUri)
        val fingerprints = withContext(Dispatchers.IO) { fingerprintDao.getAll() }
        if (fingerprints.isEmpty()) {
            throw IllegalStateException("لا توجد بصمات في فهرس البحث العكسي. ابنِ الفهرس أولًا.")
        }
        val images = withContext(Dispatchers.IO) {
            imageDao.getByIds(fingerprints.map { it.imageId }.distinct()).associateBy { it.id }
        }
        val results = ArrayList<Result>(fingerprints.size)
        for (fingerprint in fingerprints) {
            if (fingerprint.engineVersion != HaarFingerprintEngine.ENGINE_VERSION) continue
            val image = images[fingerprint.imageId] ?: continue
            val score = engine.compare(query, HaarFingerprintEngine.Fingerprint(
                fingerprint.width,
                fingerprint.height,
                fingerprint.channels,
                fingerprint.signature
            ))
            if (score.similarity >= minimumSimilarity) {
                results += Result(
                    image = image,
                    similarity = score.similarity,
                    percent = (score.similarity * 100f).toInt().coerceIn(0, 100),
                    matchedCoefficients = score.matchedCoefficients
                )
            }
        }
        val ranked = results.sortedWith(
            compareByDescending<Result> { it.similarity }
                .thenByDescending { it.image.dateTaken ?: 0L }
        ).take(limit)
        run.success("Reverse-image search completed", mapOf(
            "indexed" to fingerprints.size.toString(),
            "results" to ranked.size.toString()
        ))
        ranked
    }

    private fun decodeAndFingerprint(uri: Uri): HaarFingerprintEngine.Fingerprint {
        val bitmap = appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "تعذر قراءة صورة البحث العكسي: $uri" }
        }
        return try {
            engine.fingerprint(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = Unit
}
