package com.example.personalmemoryai.reverseimage

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** First-class local reverse-image search subsystem. */
class ReverseImageSearchService(context: Context) : AutoCloseable {
    companion object {
        private const val HAAR_WEIGHT = 0.55f
        private const val CLASSICAL_WEIGHT = 0.45f
        private const val GLOBAL_SHORTLIST_MIN = 32
        private const val GLOBAL_SHORTLIST_MAX = 64
        private const val SIFT_RERANK_LIMIT = 16
    }

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val itemDao = database.reverseImageItemDao()
    private val fingerprintDao = database.haarFingerprintDao()
    private val classicalDao = database.classicalVisualFingerprintDao()
    private val haarEngine = HaarFingerprintEngine()
    private val classicalEngine = ClassicalVisualFingerprintEngine()
    private val siftVerifier = SiftLocalVerifier()
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

    data class SearchProgress(
        val stage: String,
        val processed: Int,
        val total: Int,
        val shortlist: Int = 0,
        val localVerified: Int = 0,
        val siftVerified: Int = 0
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

    private data class Candidate(
        val item: ReverseImageItemEntity,
        val haarScore: HaarFingerprintEngine.Score,
        val classical: ClassicalVisualFingerprintEntity?,
        val preliminarySimilarity: Float,
        val bestClassicalQueryIndex: Int
    )

    private data class RankedCandidate(
        val candidate: Candidate,
        val similarity: Float,
        val classical: ClassicalVisualFingerprintEngine.Score?
    )

    suspend fun itemCount(): Long = withContext(Dispatchers.IO) { itemDao.count() }
    suspend fun fingerprintCount(): Long = withContext(Dispatchers.IO) { fingerprintDao.count() }
    suspend fun classicalFingerprintCount(): Long = withContext(Dispatchers.IO) { classicalDao.count() }

    suspend fun addImages(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (uri in uris.distinct()) {
            coroutineContext.ensureActive()
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
            coroutineContext.ensureActive()
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
                    val localPath = item.filePath ?: throw IllegalStateException(
                        "لا يوجد مسار محلي محفوظ للصورة: ${item.displayName}"
                    )
                    val bitmap = BitmapFactory.decodeFile(localPath)
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
                if (t is kotlinx.coroutines.CancellationException) throw t
                failed++
                run.failure("ITEM_${originalItem.id}", t)
            } finally {
                onProgress(IndexProgress(index + 1, items.size, indexed, skipped, failed, localFeatureIndexed))
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
        minimumSimilarity: Float = 0.35f,
        onProgress: (SearchProgress) -> Unit = {}
    ): List<Result> = withContext(Dispatchers.Default) {
        val run = diagnostics.begin(
            "REVERSE_IMAGE_SEARCH",
            mapOf(
                "limit" to limit.toString(),
                "minimum" to minimumSimilarity.toString(),
                "haar" to HaarFingerprintEngine.ENGINE_VERSION,
                "classical" to ClassicalVisualFingerprintEngine.ENGINE_VERSION,
                "strategy" to "global-shortlist-64-akaze-ransac-sift-16"
            )
        )
        val queryBitmap = resolver.openInputStream(queryUri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) {
                "تعذر قراءة صورة البحث العكسي: $queryUri"
            }
        }
        try {
            coroutineContext.ensureActive()
            onProgress(SearchProgress("تحضير صورة البحث", 0, 1))

            val haarQueries = buildHaarQueryVariants(queryBitmap)
            val classicalQueries = buildClassicalQueryVariants(queryBitmap)
            val fingerprints = withContext(Dispatchers.IO) { fingerprintDao.getAll() }
            val classicalFingerprints = withContext(Dispatchers.IO) {
                classicalDao.getAll()
                    .asSequence()
                    .filter { it.engineVersion == ClassicalVisualFingerprintEngine.ENGINE_VERSION }
                    .associateBy { it.itemId }
            }
            if (fingerprints.isEmpty()) {
                throw IllegalStateException("لا توجد بصمات في الفهرس. أضف الصور ثم ابنِ الفهرس.")
            }
            val items = withContext(Dispatchers.IO) {
                itemDao.getByIds(fingerprints.map { it.itemId }.distinct()).associateBy { it.id }
            }

            // Stage 1: global retrieval over the entire corpus. No local OpenCV here.
            val candidates = ArrayList<Candidate>(fingerprints.size)
            onProgress(SearchProgress("البحث العالمي", 0, fingerprints.size))
            for ((processed, fp) in fingerprints.withIndex()) {
                coroutineContext.ensureActive()
                if (fp.engineVersion != HaarFingerprintEngine.ENGINE_VERSION) continue
                val item = items[fp.itemId] ?: continue
                val targetHaar = HaarFingerprintEngine.Fingerprint(fp.width, fp.height, fp.channels, fp.signature)

                var bestHaar = HaarFingerprintEngine.Score(0f, 0)
                for (query in haarQueries) {
                    val score = haarEngine.compare(query, targetHaar)
                    if (score.similarity > bestHaar.similarity) bestHaar = score
                }

                val classicalEntity = classicalFingerprints[fp.itemId]
                var bestGlobalClassical: ClassicalVisualFingerprintEngine.Score? = null
                var bestQueryIndex = 0
                if (classicalEntity != null) {
                    val targetClassical = classicalEntity.toFingerprint()
                    for (index in classicalQueries.indices) {
                        coroutineContext.ensureActive()
                        val score = classicalEngine.compare(
                            classicalQueries[index], targetClassical, runLocal = false
                        )
                        if (bestGlobalClassical == null || score.similarity > bestGlobalClassical!!.similarity) {
                            bestGlobalClassical = score
                            bestQueryIndex = index
                        }
                    }
                }

                val preliminary = if (bestGlobalClassical != null) {
                    (bestHaar.similarity * HAAR_WEIGHT +
                        bestGlobalClassical.similarity * CLASSICAL_WEIGHT).coerceIn(0f, 1f)
                } else {
                    bestHaar.similarity
                }
                candidates += Candidate(
                    item = item,
                    haarScore = bestHaar,
                    classical = classicalEntity,
                    preliminarySimilarity = preliminary,
                    bestClassicalQueryIndex = bestQueryIndex
                )

                if ((processed + 1) % 16 == 0 || processed + 1 == fingerprints.size) {
                    onProgress(SearchProgress("البحث العالمي", processed + 1, fingerprints.size))
                }
            }

            // Keep the expensive stages small enough for an Android device.
            val shortlistSize = maxOf(
                GLOBAL_SHORTLIST_MIN,
                minOf(GLOBAL_SHORTLIST_MAX, maxOf(limit + 14, 48))
            )
            val shortlist = candidates
                .sortedWith(
                    compareByDescending<Candidate> { it.preliminarySimilarity }
                        .thenByDescending { it.haarScore.matchedCoefficients }
                )
                .take(shortlistSize)

            // Stage 2: one AKAZE/RANSAC verification per candidate, using only its best global query variant.
            val reranked = ArrayList<RankedCandidate>(shortlist.size)
            var localVerified = 0
            onProgress(SearchProgress("التحقق الهندسي AKAZE/RANSAC", 0, shortlist.size, shortlist.size))
            for ((index, candidate) in shortlist.withIndex()) {
                coroutineContext.ensureActive()
                val finalClassical = candidate.classical?.let { entity ->
                    classicalEngine.compare(
                        classicalQueries[candidate.bestClassicalQueryIndex],
                        entity.toFingerprint(),
                        runLocal = true
                    )
                }
                if (finalClassical?.ransacInliers ?: 0 >= 4) localVerified++
                val finalSimilarity = if (finalClassical != null) {
                    (candidate.haarScore.similarity * HAAR_WEIGHT +
                        finalClassical.similarity * CLASSICAL_WEIGHT).coerceIn(0f, 1f)
                } else {
                    candidate.haarScore.similarity
                }
                if (finalSimilarity >= minimumSimilarity) {
                    reranked += RankedCandidate(candidate, finalSimilarity, finalClassical)
                }
                onProgress(
                    SearchProgress(
                        "التحقق الهندسي AKAZE/RANSAC",
                        index + 1,
                        shortlist.size,
                        shortlist.size,
                        localVerified
                    )
                )
            }

            reranked.sortWith(
                compareByDescending<RankedCandidate> { it.similarity }
                    .thenByDescending { it.candidate.haarScore.matchedCoefficients }
                    .thenByDescending { it.classical?.ransacInliers ?: 0 }
                    .thenByDescending { it.classical?.localMatches ?: 0 }
            )

            // Stage 3: SIFT only for the top 16 after AKAZE has already reduced the set.
            val siftPool = reranked.take(minOf(SIFT_RERANK_LIMIT, reranked.size))
            var siftVerified = 0
            var siftStrong = 0
            onProgress(SearchProgress("التحقق الإضافي SIFT/RANSAC", 0, siftPool.size, shortlist.size, localVerified, 0))

            for ((index, ranked) in siftPool.withIndex()) {
                coroutineContext.ensureActive()
                val targetPath = ranked.candidate.item.filePath
                val targetBitmap = targetPath?.let { BitmapFactory.decodeFile(it) }
                if (targetBitmap != null) {
                    try {
                        val result = siftVerifier.compare(queryBitmap, targetBitmap)
                        siftVerified++
                        if (result.inliers >= 4) {
                            siftStrong++
                            ranked.classical?.let { base ->
                                val improvedLocal = maxOf(base.localSimilarity, result.similarity)
                                val improvedClassical = base.copy(
                                    similarity = maxOf(
                                        base.similarity,
                                        (base.similarity * 0.75f + improvedLocal * 0.25f).coerceIn(0f, 1f)
                                    ),
                                    localSimilarity = improvedLocal,
                                    localMatches = maxOf(base.localMatches, result.goodMatches),
                                    ransacInliers = maxOf(base.ransacInliers, result.inliers)
                                )
                                val improvedOverall = (
                                    ranked.candidate.haarScore.similarity * HAAR_WEIGHT +
                                        improvedClassical.similarity * CLASSICAL_WEIGHT
                                    ).coerceIn(0f, 1f)
                                val replacement = RankedCandidate(
                                    ranked.candidate,
                                    maxOf(ranked.similarity, improvedOverall),
                                    improvedClassical
                                )
                                for (r in reranked.indices) {
                                    if (reranked[r].candidate.item.id == ranked.candidate.item.id) {
                                        reranked[r] = replacement
                                        break
                                    }
                                }
                            }
                        }
                    } finally {
                        targetBitmap.recycle()
                    }
                }
                onProgress(
                    SearchProgress(
                        "التحقق الإضافي SIFT/RANSAC",
                        index + 1,
                        siftPool.size,
                        shortlist.size,
                        localVerified,
                        siftVerified
                    )
                )
            }

            reranked.sortWith(
                compareByDescending<RankedCandidate> { it.similarity }
                    .thenByDescending { it.candidate.haarScore.matchedCoefficients }
                    .thenByDescending { it.classical?.ransacInliers ?: 0 }
                    .thenByDescending { it.classical?.localMatches ?: 0 }
            )

            val ranked = ArrayList<Result>(minOf(limit, reranked.size))
            for (entry in reranked.take(limit)) {
                coroutineContext.ensureActive()
                val durableItem = withContext(Dispatchers.IO) { ensurePrivateCopy(entry.candidate.item) }
                val c = entry.classical
                ranked += Result(
                    item = durableItem,
                    similarity = entry.similarity,
                    percent = (entry.similarity * 100f).toInt().coerceIn(0, 100),
                    matchedCoefficients = entry.candidate.haarScore.matchedCoefficients,
                    phashPercent = ((c?.phashSimilarity ?: 0f) * 100f).toInt(),
                    dhashPercent = ((c?.dhashSimilarity ?: 0f) * 100f).toInt(),
                    colorPercent = ((c?.colorSimilarity ?: 0f) * 100f).toInt(),
                    edgePercent = ((c?.edgeSimilarity ?: 0f) * 100f).toInt(),
                    localPercent = ((c?.localSimilarity ?: 0f) * 100f).toInt(),
                    localMatches = c?.localMatches ?: 0,
                    ransacInliers = c?.ransacInliers ?: 0
                )
            }

            run.success(
                "Reverse-image staged multi-signal search completed",
                mapOf(
                    "indexed" to fingerprints.size.toString(),
                    "candidates" to candidates.size.toString(),
                    "shortlist" to shortlist.size.toString(),
                    "results" to ranked.size.toString(),
                    "localVerified" to localVerified.toString(),
                    "siftVerified" to siftVerified.toString(),
                    "siftStrong" to siftStrong.toString(),
                    "signals" to "haar,phash,dhash,hsv256,shape,akaze-mutual,sift,ransac"
                )
            )
            ranked
        } finally {
            queryBitmap.recycle()
        }
    }

    private fun buildHaarQueryVariants(bitmap: Bitmap): List<HaarFingerprintEngine.Fingerprint> {
        val result = ArrayList<HaarFingerprintEngine.Fingerprint>(7)
        result += haarEngine.fingerprint(bitmap)
        for (degrees in intArrayOf(90, 180, 270)) {
            val rotated = rotate(bitmap, degrees)
            try { result += haarEngine.fingerprint(rotated) } finally { rotated.recycle() }
        }
        for (fraction in floatArrayOf(0.92f, 0.82f, 0.72f)) {
            val crop = haarEngine.cropCentered(bitmap, fraction)
            try { result += haarEngine.fingerprint(crop) } finally { crop.recycle() }
        }
        return result
    }

    private fun buildClassicalQueryVariants(bitmap: Bitmap): List<ClassicalVisualFingerprintEngine.Fingerprint> {
        val result = ArrayList<ClassicalVisualFingerprintEngine.Fingerprint>(7)
        result += classicalEngine.fingerprint(bitmap)
        for (degrees in intArrayOf(90, 180, 270)) {
            val rotated = rotate(bitmap, degrees)
            try { result += classicalEngine.fingerprint(rotated) } finally { rotated.recycle() }
        }
        for (fraction in floatArrayOf(0.92f, 0.82f, 0.72f)) {
            val crop = haarEngine.cropCentered(bitmap, fraction)
            try { result += classicalEngine.fingerprint(crop) } finally { crop.recycle() }
        }
        return result
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun ClassicalVisualFingerprintEntity.toFingerprint(): ClassicalVisualFingerprintEngine.Fingerprint =
        ClassicalVisualFingerprintEngine.Fingerprint(
            phash = phash,
            dhash = dhash,
            colorHistogram = colorHistogram,
            edgeHistogram = edgeHistogram,
            keypoints = localKeypoints,
            descriptors = localDescriptors,
            descriptorRows = localDescriptorRows,
            descriptorCols = localDescriptorCols,
            descriptorType = localDescriptorType
        )

    private suspend fun ensurePrivateCopy(item: ReverseImageItemEntity): ReverseImageItemEntity {
        val existing = item.filePath?.let(::File)
        if (existing?.isFile == true && existing.length() > 0L) return item
        val copied = copyToPrivateLibrary(Uri.parse(item.uri))
            ?: throw IllegalStateException("تعذر استعادة النسخة المحلية: ${item.displayName}")
        val updated = item.copy(filePath = copied.absolutePath, fileSize = copied.length())
        itemDao.upsert(updated)
        return updated
    }

    private fun copyToPrivateLibrary(source: Uri): File? = try {
        val safeName = displayName(source)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(100)
            .ifBlank { "image" }
        val target = File(libraryDirectory, "${UUID.randomUUID()}_$safeName")
        resolver.openInputStream(source)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024) }
        } ?: return null
        if (target.length() <= 0L) { target.delete(); null } else target
    } catch (_: Throwable) { null }

    private fun displayName(uri: Uri): String = resolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment ?: "image"

    private fun fileSize(uri: Uri): Long = resolver.query(
        uri, arrayOf(OpenableColumns.SIZE), null, null, null
    )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } ?: 0L

    override fun close() = Unit
}
