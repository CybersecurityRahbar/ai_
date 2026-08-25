package com.example.personalmemoryai.reverseimage

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Standalone local reverse-image search. It does not use MobileCLIP, OCR, faces or legacy image indexing. */
class ReverseImageSearchService(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val itemDao = database.reverseImageItemDao()
    private val fingerprintDao = database.haarFingerprintDao()
    private val engine = HaarFingerprintEngine()
    private val diagnostics = DiagnosticsManager.get(appContext)
    private val resolver: ContentResolver = appContext.contentResolver

    data class IndexProgress(val processed: Int, val total: Int, val indexed: Int, val skipped: Int, val failed: Int)
    data class Result(
        val item: ReverseImageItemEntity,
        val similarity: Float,
        val percent: Int,
        val matchedCoefficients: Int
    )

    suspend fun itemCount(): Long = withContext(Dispatchers.IO) { itemDao.count() }
    suspend fun fingerprintCount(): Long = withContext(Dispatchers.IO) { fingerprintDao.count() }

    suspend fun addImages(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (uri in uris.distinct()) {
            if (itemDao.findByUri(uri.toString()) != null) continue
            val bitmap = resolver.openInputStream(uri).use { input ->
                requireNotNull(BitmapFactory.decodeStream(input)) { "تعذر قراءة الصورة: $uri" }
            }
            val item = try {
                ReverseImageItemEntity(
                    uri = uri.toString(),
                    displayName = displayName(uri),
                    filePath = uri.toString(),
                    fileSize = fileSize(uri),
                    width = bitmap.width,
                    height = bitmap.height,
                    mimeType = resolver.getType(uri),
                    sourceModifiedAt = null
                )
            } finally {
                bitmap.recycle()
            }
            val id = itemDao.insert(item)
            if (id != -1L) added++
        }
        added
    }

    suspend fun buildIndex(rebuild: Boolean = false, onProgress: (IndexProgress) -> Unit = {}): IndexProgress = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("REVERSE_IMAGE_INDEX", mapOf("rebuild" to rebuild.toString(), "engine" to HaarFingerprintEngine.ENGINE_VERSION))
        val items = withContext(Dispatchers.IO) { itemDao.getAll() }
        var indexed = 0
        var skipped = 0
        var failed = 0

        for ((index, item) in items.withIndex()) {
            try {
                val existing = withContext(Dispatchers.IO) { fingerprintDao.getForItem(item.id) }
                val unchanged = existing != null &&
                    existing.engineVersion == HaarFingerprintEngine.ENGINE_VERSION &&
                    existing.sourceModifiedAt == item.sourceModifiedAt
                if (!rebuild && unchanged) {
                    skipped++
                } else {
                    val fp = decodeAndFingerprint(Uri.parse(item.uri))
                    withContext(Dispatchers.IO) {
                        fingerprintDao.insert(
                            HaarFingerprintEntity(
                                itemId = item.id,
                                engineVersion = HaarFingerprintEngine.ENGINE_VERSION,
                                sourceModifiedAt = item.sourceModifiedAt,
                                width = fp.width,
                                height = fp.height,
                                channels = fp.channels,
                                signature = fp.signature
                            )
                        )
                    }
                    indexed++
                }
            } catch (t: Throwable) {
                failed++
                run.failure("ITEM_${item.id}", t)
            } finally {
                onProgress(IndexProgress(index + 1, items.size, indexed, skipped, failed))
            }
        }

        run.success(
            "Reverse-image fingerprint index completed",
            mapOf(
                "items" to items.size.toString(),
                "indexed" to indexed.toString(),
                "skipped" to skipped.toString(),
                "failed" to failed.toString()
            )
        )
        IndexProgress(items.size, items.size, indexed, skipped, failed)
    }

    suspend fun search(queryUri: Uri, limit: Int = 50, minimumSimilarity: Float = 0.35f): List<Result> = withContext(Dispatchers.Default) {
        val run = diagnostics.begin(
            "REVERSE_IMAGE_SEARCH",
            mapOf("limit" to limit.toString(), "minimum" to minimumSimilarity.toString(), "engine" to HaarFingerprintEngine.ENGINE_VERSION)
        )

        val queryBitmaps = decodeQueryBitmaps(queryUri)
        val queryFingerprints = try {
            buildQueryFingerprints(queryBitmaps)
        } finally {
            queryBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        }

        val fingerprints = withContext(Dispatchers.IO) { fingerprintDao.getAll() }
        if (fingerprints.isEmpty()) {
            throw IllegalStateException("لا توجد بصمات في فهرس البحث العكسي. أضف الصور ثم ابنِ الفهرس.")
        }

        val items = withContext(Dispatchers.IO) {
            itemDao.getByIds(fingerprints.map { it.itemId }.distinct()).associateBy { it.id }
        }

        val results = ArrayList<Result>(fingerprints.size)
        for (fp in fingerprints) {
            if (fp.engineVersion != HaarFingerprintEngine.ENGINE_VERSION) continue
            val item = items[fp.itemId] ?: continue
            val target = HaarFingerprintEngine.Fingerprint(fp.width, fp.height, fp.channels, fp.signature)

            var best = HaarFingerprintEngine.Score(0f, 0)
            for (query in queryFingerprints) {
                val score = engine.compare(query, target)
                if (score.similarity > best.similarity) best = score
            }

            if (best.similarity >= minimumSimilarity) {
                results += Result(
                    item = item,
                    similarity = best.similarity,
                    percent = (best.similarity * 100f).toInt().coerceIn(0, 100),
                    matchedCoefficients = best.matchedCoefficients
                )
            }
        }

        val ranked = results
            .sortedWith(compareByDescending<Result> { it.similarity }.thenBy { it.item.displayName.lowercase() })
            .take(limit)

        run.success(
            "Reverse-image search completed",
            mapOf("indexed" to fingerprints.size.toString(), "results" to ranked.size.toString(), "queryVariants" to queryFingerprints.size.toString())
        )
        ranked
    }

    /** Query variants improve robustness to screenshots, borders and moderate crops. */
    private fun buildQueryFingerprints(bitmaps: List<Bitmap>): List<HaarFingerprintEngine.Fingerprint> {
        val result = ArrayList<HaarFingerprintEngine.Fingerprint>(bitmaps.size * 4)
        val original = bitmaps.first()
        result += engine.fingerprint(original)

        val fractions = floatArrayOf(0.92f, 0.82f, 0.72f)
        for (fraction in fractions) {
            val crop = engine.cropCentered(original, fraction)
            try {
                result += engine.fingerprint(crop)
            } finally {
                crop.recycle()
            }
        }
        return result
    }

    private fun decodeQueryBitmaps(uri: Uri): List<Bitmap> {
        val bitmap = resolver.openInputStream(uri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "تعذر قراءة صورة البحث العكسي: $uri" }
        }
        return listOf(bitmap)
    }

    private fun decodeAndFingerprint(uri: Uri): HaarFingerprintEngine.Fingerprint {
        val bitmap = resolver.openInputStream(uri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "تعذر قراءة الصورة: $uri" }
        }
        return try {
            engine.fingerprint(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun displayName(uri: Uri): String = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "image"

    private fun fileSize(uri: Uri): Long = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
    } ?: 0L

    override fun close() = Unit
}
