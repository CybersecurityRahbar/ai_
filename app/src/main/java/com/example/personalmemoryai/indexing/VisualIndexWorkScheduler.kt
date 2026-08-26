package com.example.personalmemoryai.indexing

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

object VisualIndexWorkScheduler {
    fun enqueue(context: Context, rebuild: Boolean = false): UUID {
        val request = OneTimeWorkRequestBuilder<UnifiedVisualIndexWorker>()
            .setInputData(workDataOf(UnifiedVisualIndexWorker.KEY_REBUILD to rebuild))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UnifiedVisualIndexWorker.WORK_NAME,
            if (rebuild) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
        return request.id
    }

    fun getInfo(context: Context, id: UUID): WorkInfo? =
        WorkManager.getInstance(context.applicationContext).getWorkInfoById(id).get()

    fun progressData(info: WorkInfo): Data = info.progress
}
