package com.example.personalmemoryai.indexing

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.example.personalmemoryai.advancedvisual.AdvancedVisualFingerprintEngine
import com.example.personalmemoryai.advancedvisual.AdvancedVisualFingerprintEntity
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.reverseimage.ClassicalVisualFingerprintEngine
import com.example.personalmemoryai.reverseimage.ClassicalVisualFingerprintEntity
import com.example.personalmemoryai.reverseimage.HaarFingerprintEngine
import com.example.personalmemoryai.reverseimage.HaarFingerprintEntity
import com.example.personalmemoryai.reverseimage.ReverseImageItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Background index runner with zero-recall-loss parallel feature extraction and batched Room persistence. */
class OptimizedUnifiedVisualIndexService(context: Context) : AutoCloseable {
    companion object {
        const val BATCH_SIZE = 16
        const val PARALLELISM = 4
    }

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val itemDao = database.reverseImageItemDao()
    private val haarDao = database.haarFingerprintDao()
    private val classicalDao = database.classicalVisualFingerprintDao()
    private val advancedDao = database.advancedVisualFingerprintDao()
    private val batchDao = database.visualIndexBatchDao()
    private val resolver: ContentResolver = appContext.contentResolver
    private val diagnostics = DiagnosticsManager.get(appContext)
    private val haarEngine = HaarFingerprintEngine()
    private val classicalEngine = ClassicalVisualFingerprintEngine()
    private val advancedEngine = AdvancedVisualFingerprintEngine()
    private val libraryDirectory = File(appContext.filesDir, "reverse_image/library").also { it.mkdirs() }
    private val cpuDispatcher = Dispatchers.Default.limitedParallelism(PARALLELISM)

    data class Progress(
        val processed: Int,
        val total: Int,
        val indexed: Int,
        val skipped: Int,
        val failed: Int,
        val localFeatures: Int
    )

    private sealed interface ItemResult {
        data object Skipped : ItemResult
        data class Ready(val prepared: Prepared) : ItemResult
        data class Failed(val itemId: Long, val error: Throwable) : ItemResult
    }

    private data class Prepared(
        val haar: HaarFingerprintEntity,
        val classical: ClassicalVisualFingerprintEntity,
        val advanced: AdvancedVisualFingerprintEntity,
        val localFeatures: Int
    )

    suspend fun run(rebuild: Boolean = false, onProgress: suspend (Progress) -> Unit = {}): Progress = withContext(Dispatchers.Default) {
        val run = diagnostics.begin(
            "REVERSE_IMAGE_INDEX",
            mapOf(
                "rebuild" to rebuild.toString(),
                "haar" to HaarFingerprintEngine.ENGINE_VERSION,
                "classical" to ClassicalVisualFingerprintEngine.ENGINE_VERSION,
                "advanced" to AdvancedVisualFingerprintEngine.ENGINE_VERSION,
                "sharedDecode" to "true",
                "batchedPersistence" to "true",
                "batchSize" to BATCH_SIZE.toString(),
                "parallelism" to PARALLELISM.toString()
            )
        )

        val items = withContext(Dispatchers.IO) { itemDao.getAll() }
        val total = items.size
        if (total == 0) {
            val empty = Progress(0, 0, 0, 0, 0, 0)
            onProgress(empty)
            run.success("Shared visual index completed: empty corpus")
            return@withContext empty
        }

        val oldHaar = withContext(Dispatchers.IO) { haarDao.getAll().associateBy { it.itemId } }
        val oldClassical = withContext(Dispatchers.IO) { classicalDao.getAll().associateBy { it.itemId } }
        val oldAdvanced = withContext(Dispatchers.IO) {
            advancedDao.getAll(AdvancedVisualFingerprintEngine.ENGINE_VERSION).associateBy { it.itemId }
        }

        var processed = 0
        var indexed = 0
        var skipped = 0
        var failed = 0
        var localFeatures = 0

        for (batch in items.chunked(BATCH_SIZE)) {
            coroutineContext.ensureActive()

            val results: List<ItemResult> = coroutineScope {
                batch.map { item ->
                    async(cpuDispatcher) {
                        try {
                            coroutineContext.ensureActive()
                            val current = ensurePrivateCopy(item)
                            val unchanged = !rebuild &&
                                oldHaar[current.id]?.engineVersion == HaarFingerprintEngine.ENGINE_VERSION &&
                                oldClassical[current.id]?.engineVersion == ClassicalVisualFingerprintEngine.ENGINE_VERSION &&
                                oldAdvanced[current.id]?.engineVersion == AdvancedVisualFingerprintEngine.ENGINE_VERSION
                            if (unchanged) return@async ItemResult.Skipped

                            val path = current.filePath
                                ?: return@async ItemResult.Failed(
                                    current.id,
                                    IllegalStateException("لا يوجد مسار محلي محفوظ للصورة: ${current.displayName}")
                                )

                            val bitmap = BitmapFactory.decodeFile(path)
                                ?: return@async ItemResult.Failed(
                                    current.id,
                                    IllegalStateException("تعذر فك ترميز الصورة: ${current.displayName}")
                                )

                            try {
                                val haar = haarEngine.fingerprint(bitmap)
                                val classical = classicalEngine.fingerprint(bitmap)
                                val advanced = advancedEngine.fingerprint(bitmap)

                                ItemResult.Ready(
                                    Prepared(
                                        haar = HaarFingerprintEntity(
                                            itemId = current.id,
                                            engineVersion = HaarFingerprintEngine.ENGINE_VERSION,
                                            sourceModifiedAt = current.sourceModifiedAt,
                                            width = haar.width,
                                            height = haar.height,
                                            channels = haar.channels,
                                            signature = haar.signature
                                        ),
                                        classical = ClassicalVisualFingerprintEntity(
                                            itemId = current.id,
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
                                        ),
                                        advanced = AdvancedVisualFingerprintEntity(
                                            itemId = current.id,
                                            engineVersion = AdvancedVisualFingerprintEngine.ENGINE_VERSION,
                                            grayPyramid = advanced.grayPyramid,
                                            colorMoments = advanced.colorMoments,
                                            lbpHistogram = advanced.lbpHistogram,
                                            gradientHistogram = advanced.gradientHistogram,
                                            layoutSignature = advanced.layoutSignature,
                                            entropy = advanced.entropy,
                                            aspectRatio = advanced.aspectRatio
                                        ),
                                        localFeatures = if (classical.keypoints != null && classical.descriptors != null) 1 else 0
                                    )
                                )
                            } finally {
                                bitmap.recycle()
                            }
                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) throw t
                            ItemResult.Failed(item.id, t)
                        }
                    }
                }.awaitAll()
            }

            val ready = results.mapNotNull { (it as? ItemResult.Ready)?.prepared }
            if (ready.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    batchDao.insertBatch(
                        haar = ready.map { it.haar },
                        classical = ready.map { it.classical },
                        advanced = ready.map { it.advanced }
                    )
                }
            }

            val failedItems = results.mapNotNull { it as? ItemResult.Failed }
            failedItems.forEach { failure -> run.failure("ITEM_${failure.itemId}", failure.error) }

            indexed += ready.size
            skipped += results.count { it === ItemResult.Skipped }
            failed += failedItems.size
            localFeatures += ready.sumOf { it.localFeatures }
            processed += batch.size
            onProgress(Progress(processed, total, indexed, skipped, failed, localFeatures))
        }

        val result = Progress(processed, total, indexed, skipped, failed, localFeatures)
        run.success(
            "Optimized batched shared visual index completed",
            mapOf(
                "items" to total.toString(),
                "indexed" to indexed.toString(),
                "skipped" to skipped.toString(),
                "failed" to failed.toString(),
                "localFeatureIndexed" to localFeatures.toString(),
                "batchSize" to BATCH_SIZE.toString(),
                "parallelism" to PARALLELISM.toString()
            )
        )
        result
    }

    private suspend fun ensurePrivateCopy(item: ReverseImageItemEntity): ReverseImageItemEntity {
        val existing = item.filePath?.let(::File)
        if (existing?.isFile == true && existing.length() > 0L) return item

        val source = Uri.parse(item.uri)
        val safeName = displayName(source)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(100)
            .ifBlank { "image" }
        val target = File(libraryDirectory, "${UUID.randomUUID()}_$safeName")

        withContext(Dispatchers.IO) {
            resolver.openInputStream(source)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: throw IllegalStateException("تعذر قراءة المصدر: ${item.uri}")
        }

        if (!target.isFile || target.length() <= 0L) {
            target.delete()
            throw IllegalStateException("تعذر إنشاء النسخة المحلية: ${item.displayName}")
        }

        val updated = item.copy(filePath = target.absolutePath, fileSize = target.length())
        withContext(Dispatchers.IO) { itemDao.upsert(updated) }
        return updated
    }

    private fun displayName(uri: Uri): String = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "image"

    override fun close() = Unit
}
