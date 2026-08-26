package com.example.personalmemoryai.indexing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.personalmemoryai.R
import com.example.personalmemoryai.reverseimage.ReverseImageSearchService

class UnifiedVisualIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "pmai-shared-visual-index"
        const val KEY_REBUILD = "rebuild"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_INDEXED = "indexed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_LOCAL_FEATURES = "localFeatures"
        const val CHANNEL_ID = "visual_indexing"
        private const val NOTIFICATION_ID = 4107
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("تهيئة الفهرس البصري المشترك…"))
        val rebuild = inputData.getBoolean(KEY_REBUILD, false)
        val service = ReverseImageSearchService(applicationContext)
        return try {
            service.buildIndex(rebuild) { progress ->
                val percent = if (progress.total > 0) {
                    ((progress.processed * 100L) / progress.total).toInt().coerceIn(0, 100)
                } else 100
                setProgressAsync(workDataOf(
                    KEY_PROCESSED to progress.processed,
                    KEY_TOTAL to progress.total,
                    KEY_INDEXED to progress.indexed,
                    KEY_SKIPPED to progress.skipped,
                    KEY_FAILED to progress.failed,
                    KEY_LOCAL_FEATURES to progress.localFeatureIndexed,
                    "percent" to percent
                ))
                setForegroundAsync(createForegroundInfo("فهرسة الصور $percent% • ${progress.processed}/${progress.total}"))
            }
            Result.success()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Result.failure(workDataOf("error" to (t.message ?: t.javaClass.simpleName)))
        } finally {
            service.close()
        }
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Visual indexing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress for long-running local visual indexing"
                }
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Personal Memory AI")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
