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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * First-class local reverse-image search subsystem.
 *
 * The source URI is metadata only. A durable private copy is created for every corpus image,
 * so indexing/search/result rendering never depend on transient DocumentsProvider permissions.
 */
class ReverseImageSearchService(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val itemDao = database.reverseImageItemDao()
    private val fingerprintDao = database.haarFingerprintDao()
    private val classicalDao = database.classicalVisualFingerprintDao()
    private val haarEngine = HaarFingerprintEngine()
    private val classicalEngine = ClassicalVisualFingerprintEngine()
    private val diagnostics = DiagnosticsManager.get(appContext)
    private val resolver: ContentResolver = appContext.contentResolver

    private val libraryDirectory: File
        get() = File(appContext.filesDir, "reverse_image/library").also { it.mkdirs() }

    data class IndexProgress(
        val processed: Int,
        val total: Int,
        val indexed: Int,
        val skipped: Int,
        val failed: Int,
        val localFeatureIndexed: Int
    )

    data class Result(
        val item: ReverseImageItemEntity,
        val similarity: Float,
        val percent: Int,
        val matchedCoefficients: Int,
        val phashPercent: Int,
        val dhashPercent: Int,
        val colorPercent: Int,
        val edgePercent: Int,
        val localPercent: Int,
        val localMatches: Int,
        val ransacInliers: Int
    )

    suspend fun itemCount(): Long = withContext(Dispatchers.IO) { itemDao.count() }
    suspend fun fingerprintCount(): Long = withContext(Dispatchers.IO) { fingerprintDao.count() }
    suspend fun classicalFingerprintCount(): Long = withContext(Dispatchers.IO) { classicalDao.count() }

    suspend fun addImages(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (uri in uris.distinct()) {
            if (itemDao.findByUri(uri.toString()) != null) continue
            val localFile = copyToPrivateLibrary(uri)
                ?: throw IllegalStateException("تعذر حفظ الصورة محليًا: $uri")
            try {
                val bitmap = BitmapFactory.decodeFile(localFile.absolutePath)
                    ?: throw IllegalStateException("تعذر فك ترميز الصورة: $uri")
                try {
                    val item = ReverseImageItemEntity(
                        uri = uri.toString(),
                        displayName = displayName(uri),
                        filePath = localFile.absolutePath,
                        fileSize = localFile.length().takeIf { it > 0L } ?: fileSize(uri),
                        width = bitmap.width,
                        height = bitmap.height,
                        mimeType = resolver.getType(uri),
                        sourceModifiedAt = null
                    )
                    val id = itemDao.insert(item)
                    if (id != -1L) added++ else localFile.delete()
                } finally {
                    bitmap.recycle()
                }
            } catch (t: Throwable) {
                localFile.delete()
                throw t
            }
        }
        added
    }

    suspend fun buildIndex(
        rebuild: Boolean = false,
        onProgress: (IndexProgress) -> Unit = {}
    ): IndexProgress = withContext(Dispatchers.Default) {
        val run = diagnostics.begin(
            "REVERSE_IMAGE_INDEX",
            mapOf(
                "rebuild" to rebuild.toString(),
                "haar" to HaarFingerprintEngine.ENGINE_VERSION,
                "classical" to ClassicalVisualFingerprintEngine.ENGINE_VERSION
            )
        )
        val items = withContext(Dispatchers.IO) { itemDao.getAll() }
        var indexed = 0
        var skipped = 0
        var failed = 0
        var localFeatureIndexed = 0

        for ((index, originalItem) in items.withIndex()) {
            try {
                val item = withContext(Dispatchers.IO) { ensurePrivateCopy(originalItem) }
                val oldHaar = withContext(Dispatchers.IO) { fingerprintDao.getForItem(item.id) }
                val oldClassical = withContext(Dispatchers.IO) { classicalDao.getForItem(item.id) }
                val unchanged = !rebuild &&
                    oldHaar?.engineVersion == HaarFingerprintEngine.ENGINE_VERSION &&
                    oldClassical?.engineVersion == ClassicalVisualFingerprintEngine.ENGINE_VERSION

                if (unchanged) {
                    skipped++
                } else {
                    val bitmap = BitmapFactory.decodeFile(item.filePath)
                        ?: throw IllegalStateException("تعذر قراءة النسخة المحلية: ${item.displayName}")
                    try {
                        val haar = haarEngine.fingerprint(bitmap)
                        val classical = classicalEngine.fingerprint(bitmap)
                        withContext(Dispatchers.IO) {
                            fingerprintDao.insert(
                                HaarFingerprintEntity(
                                    itemId = item.id,
                                    engineVersion = HaarFingerprintEngine.ENGINE_VERSION,
                                    sourceModifiedAt = item.sourceModifiedAt,
                                    width = haar.width,
                                    height = haar.height,
                                    channels = haar.channels,
                                    signature = haar.signature
                                )
                            )
                            classicalDao.insert(
                                ClassicalVisualFingerprintEntity(
                                    itemId = item.id,
                                    engineVersion = ClassicalVisualFingerprintEngine.ENGINE_VERSION,
                                    phash = classical.phash,
                                    dhash = classical.dhash,
                                    colorHistogram = classical.colorHistogram,
                                    edgeHistogram = classical.edgeHistogram,
                                    localKeypoints = classical.keypoints,
                                    localDescriptors = classical.descriptors,
                                    localDescriptorRows = classical.descriptorRows,
                                    localDescriptorCols = classical.descriptorCols,
                                    localDescriptorType = classical.descriptorType
                                )
                            )
                        }
                        indexed++
                        if (classical.keypoints != null && classical.descriptors != null) localFeatureIndexed++
                    } finally {
                        bitmap.recycle()
                    }
                }
            } catch (t: Throwable) {
                failed++
                run.failure("ITEM_${originalItem.id}", t)
            } finally {
                onProgress(
                    IndexProgress(
                        processed = index + 1,
                        total = items.size,
                        indexed = indexed,
                        skipped = skipped,
                        failed = failed,
                        localFeatureIndexed = localFeatureIndexed
                    )
                )
            }
        }

        run.success(
            "Reverse-image multi-fingerprint index completed",
            mapOf(
                "items" to items.size.toString(),
                "indexed" to indexed.toString(),
                "skipped" to skipped.toString(),
                "failed" to failed.toString(),
                "localFeatureIndexed" to localFeatureIndexed.toString()
            )
        )
        IndexProgress(items.size, items.size, indexed, skipped, failed, localFeatureIndexed)
    }

    suspend fun search(
        queryUri: Uri,
        limit: Int = 50,
        minimumSimilarity: Float = 0.35f
    ): List<Result> = withContext(Dispatchers.Default) {
        val run = diagnostics.begin(
            "REVERSE_IMAGE_SEARCH",
            mapOf(
                "limit" to limit.toString(),
                "minimum" to minimumSimilarity.toString(),
                "haar" to HaarFingerprintEngine.ENGINE_VERSION,
                "classical" to ClassicalVisualFingerprintEngine.ENGINE_VERSION
            )
        )
        val queryBitmap = resolver.openInputStream(queryUri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "تعذر قراءة صورة البحث العكسي: $queryUri" }
        }
        try {
            val haarQueries = buildHaarQueryVariants(queryBitmap)
            val classicalQuery = classicalEngine.fingerprint(queryBitmap)
            val fingerprints = withContext(Dispatchers.IO) { fingerprintDao.getAll() }
            val classicalFingerprints = withContext(Dispatchers.IO) {
                classicalDao.getAll().associateBy { it.itemId }
            }
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
                val durableItem = withContext(Dispatchers.IO) { ensurePrivateCopy(item) }
                val targetHaar = HaarFingerprintEngine.Fingerprint(
                    durableItem.let { fp.width },
                    fp.height,
                    fp.channels,
                    fp.signature
                )
                var bestHaar = HaarFingerprintEngine.Score(0f, 0)
                for (query in haarQueries) {
                    val score = haarEngine.compare(query, targetHaar)
                    if (score.similarity > bestHaar.similarity) bestHaar = score
                }

                val classicalFp = classicalFingerprints[durableItem.id]
                    ?.takeIf { it.engineVersion == ClassicalVisualFingerprintEngine.ENGINE_VERSION }
                val classicalScore = classicalFp?.let {
                    classicalEngine.compare(
                        classicalQuery,
                        ClassicalVisualFingerprintEngine.Fingerprint(
                            phash = it.phash,
                            dhash = it.dhash,
                            colorHistogram = it.colorHistogram,
                            edgeHistogram = it.edgeHistogram,
                            keypoints = it.localKeypoints,
                            descriptors = it.localDescriptors,
                            descriptorRows = it.localDescriptorRows,
                            descriptorCols = it.localDescriptorCols,
                            descriptorType = it.localDescriptorType
                        )
                    )
                }

                val finalSimilarity = if (classicalScore != null) {
                    (bestHaar.similarity * 0.55f + classicalScore.similarity * 0.45f).coerceIn(0f, 1f)
                } else {
                    bestHaar.similarity
                }
                if (finalSimilarity < minimumSimilarity) continue
                results += Result(
                    item = durableItem,
                    similarity = finalSimilarity,
                    percent = (finalSimilarity * 100f).toInt().coerceIn(0, 100),
                    matchedCoefficients = bestHaar.matchedCoefficients,
                    phashPercent = ((classicalScore?.phashSimilarity ?: 0f) * 100f).toInt(),
                    dhashPercent = ((classicalScore?.dhashSimilarity ?: 0f) * 100f).toInt(),
                    colorPercent = ((classicalScore?.colorSimilarity ?: 0f) * 100f).toInt(),
                    edgePercent = ((classicalScore?.edgeSimilarity ?: 0f) * 100f).toInt(),
                    localPercent = ((classicalScore?.localSimilarity ?: 0f) * 100f).toInt(),
                    localMatches = classicalScore?.localMatches ?: 0,
                    ransacInliers = classicalScore?.ransacInliers ?: 0
                )
            }

            val ranked = results.sortedWith(
                compareByDescending<Result> { it.similarity }
                    .thenByDescending { it.ransacInliers }
                    .thenByDescending { it.localMatches }
            ).take(limit)

            run.success(
                "Reverse-image multi-signal search completed",
                mapOf(
                    "indexed" to fingerprints.size.toString(),
                    "results" to ranked.size.toString(),
                    "signals" to "haar,phash,dhash,color,edge,akaze,ransac"
                )
            )
            ranked
        } finally {
            queryBitmap.recycle()
        }
    }

    /** The original Haar image plus center crops improve robustness to moderate cropping and screenshots. */
    private fun buildHaarQueryVariants(bitmap: Bitmap): List<HaarFingerprintEngine.Fingerprint> {
        val result = ArrayList<HaarFingerprintEngine.Fingerprint>(4)
        result += haarEngine.fingerprint(bitmap)
        for (fraction in floatArrayOf(0.92f, 0.82f, 0.72f)) {
            val crop = haarEngine.cropCentered(bitmap, fraction)
            try {
                result += haarEngine.fingerprint(crop)
            } finally {
                crop.recycle()
            }
        }
        return result
    }

    private fun ensurePrivateCopy(item: ReverseImageItemEntity): ReverseImageItemEntity {
        val existing = item.filePath?.let(::File)
        if (existing?.isFile == true && existing.length() > 0L) return item

        val source = Uri.parse(item.uri)
        val copied = copyToPrivateLibrary(source)
            ?: throw IllegalStateException("تعذر استعادة النسخة المحلية: ${item.displayName}")
        val updated = item.copy(filePath = copied.absolutePath, fileSize = copied.length())
        itemDao.upsert(updated)
        return updated
    }

    private fun copyToPrivateLibrary(source: Uri): File? {
        return try {
            val safeName = displayName(source)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(100)
                .ifBlank { "image" }
            val target = File(libraryDirectory, "${UUID.randomUUID()}_$safeName")
            resolver.openInputStream(source)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output, 1024 * 1024)
                }
            } ?: return null
            if (target.length() <= 0L) {
                target.delete()
                null
            } else {
                target
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun displayName(uri: Uri): String = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "image"

    private fun fileSize(uri: Uri): Long = resolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
    } ?: 0L

    override fun close() = Unit
}
