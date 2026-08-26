package com.example.personalmemoryai.indexing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

object ImageCorpusImportScheduler {
    fun enqueue(context: Context, queueFile: String): UUID {
        val request = OneTimeWorkRequestBuilder<ImageCorpusImportWorker>()
            .setInputData(workDataOf(ImageCorpusImportWorker.KEY_QUEUE_FILE to queueFile))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ImageCorpusImportWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND,
            request
        )
        return request.id
    }
}
