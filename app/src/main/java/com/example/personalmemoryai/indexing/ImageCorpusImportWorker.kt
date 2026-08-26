package com.example.personalmemoryai.indexing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.personalmemoryai.R
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.reverseimage.ReverseImageItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Durable, streaming and memory-bounded importer for very large local image URI queues. */
class ImageCorpusImportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val WORK_NAME = "pmai-image-corpus-import"
        const val KEY_QUEUE_FILE = "queueFile"
        const val KEY_TOTAL = "total"
        const val KEY_PROCESSED = "processed"
        const val KEY_ADDED = "added"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_PERCENT = "percent"
        private const val CHANNEL_ID = "visual_import"
        private const val NOTIFICATION_ID = 4108
    }

    private val db by lazy { AppDatabase.getInstance(applicationContext) }
    private val itemDao by lazy { db.reverseImageItemDao() }
    private val diagnostics by lazy { DiagnosticsManager.get(applicationContext) }
    private val libraryDirectory by lazy { File(applicationContext.filesDir, "reverse_image/library").also { it.mkdirs() } }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("تهيئة استيراد الصور…"))
        val queuePath = inputData.getString(KEY_QUEUE_FILE) ?: return Result.failure(workDataOf("error" to "missing queue file"))
        val queue = File(queuePath)
        if (!queue.isFile) return Result.failure(workDataOf("error" to "queue file not found"))

        val total = withContext(Dispatchers.IO) { queue.useLines(Charsets.UTF_8) { lines -> lines.map(String::trim).count { it.isNotBlank() } } }
        var processed = 0
        var added = 0
        var skipped = 0
        var failed = 0
        val run = diagnostics.begin("REVERSE_IMAGE_IMPORT", mapOf("total" to total.toString(), "streaming" to "true"))

        return try {
            withContext(Dispatchers.IO) {
                queue.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.map(String::trim).filter(String::isNotBlank).iterator().let { iterator ->
                        while (iterator.hasNext()) {
                            coroutineContext.ensureActive()
                            val raw = iterator.next()
                            try {
                                val uri = Uri.parse(raw)
                                val existing = itemDao.findByUri(raw)
                                if (existing != null) {
                                    skipped++
                                } else {
                                    val localFile = copyToPrivateLibrary(uri)
                                    if (localFile == null) throw IllegalStateException("تعذر نسخ الصورة محليًا: $uri")
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(localFile.absolutePath, bounds)
                                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                                        localFile.delete()
                                        throw IllegalStateException("تعذر فك ترميز الصورة: $uri")
                                    }
                                    val entity = ReverseImageItemEntity(
                                        uri = raw,
                                        displayName = queryDisplayName(uri) ?: (uri.lastPathSegment ?: "image"),
                                        filePath = localFile.absolutePath,
                                        fileSize = localFile.length(),
                                        width = bounds.outWidth,
                                        height = bounds.outHeight,
                                        mimeType = applicationContext.contentResolver.getType(uri),
                                        sourceModifiedAt = null
                                    )
                                    val id = itemDao.insert(entity)
                                    if (id > 0L) added++ else {
                                        localFile.delete()
                                        skipped++
                                    }
                                }
                            } catch (t: Throwable) {
                                if (t is kotlinx.coroutines.CancellationException) throw t
                                failed++
                                diagnostics.begin("REVERSE_IMAGE_IMPORT_ITEM", mapOf("uri" to raw)).failure("IMPORT_ITEM", t)
                            } finally {
                                processed++
                                publishProgress(processed, total, added, skipped, failed)
                            }
                        }
                    }
                }
            }
            queue.delete()
            run.success("Durable streaming image import completed", mapOf("total" to total.toString(), "processed" to processed.toString(), "added" to added.toString(), "skipped" to skipped.toString(), "failed" to failed.toString()))
            Result.success(workDataOf(KEY_TOTAL to total, KEY_PROCESSED to processed, KEY_ADDED to added, KEY_SKIPPED to skipped, KEY_FAILED to failed, KEY_PERCENT to 100))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Result.failure(workDataOf("error" to (t.message ?: t.javaClass.simpleName), KEY_TOTAL to total, KEY_PROCESSED to processed, KEY_ADDED to added, KEY_SKIPPED to skipped, KEY_FAILED to failed))
        }
    }

    private suspend fun publishProgress(processed: Int, total: Int, added: Int, skipped: Int, failed: Int) {
        val percent = if (total > 0) ((processed * 100L) / total).toInt().coerceIn(0, 100) else 100
        setProgress(workDataOf(KEY_TOTAL to total, KEY_PROCESSED to processed, KEY_ADDED to added, KEY_SKIPPED to skipped, KEY_FAILED to failed, KEY_PERCENT to percent))
        setForeground(createForegroundInfo("استيراد الصور $percent% • $processed/$total"))
    }

    private fun copyToPrivateLibrary(source: Uri): File? = try {
        val resolver = applicationContext.contentResolver
        val safeName = (queryDisplayName(source) ?: source.lastPathSegment ?: "image")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(100).ifBlank { "image" }
        val target = File(libraryDirectory, "${UUID.randomUUID()}_$safeName")
        resolver.openInputStream(source)?.use { input -> FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024) } }
        if (target.isFile && target.length() > 0L) target else null
    } catch (_: Throwable) { null }

    private fun queryDisplayName(uri: Uri): String? = applicationContext.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Visual import", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Personal Memory AI").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTIFICATION_ID, notification, type)
    }
}
