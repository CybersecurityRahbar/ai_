package com.example.personalmemoryai.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Keeps a durable private copy of indexed images so the library survives source URI changes. */
class ManagedImageStore(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, "library/images").also { it.mkdirs() }

    fun importImage(source: Uri, preferredName: String): File? {
        return try {
            val safe = preferredName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "image" }
            val target = File(directory, "${UUID.randomUUID()}_$safe")
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: return null
            target
        } catch (_: Throwable) {
            null
        }
    }

    fun delete(filePath: String?) {
        if (filePath.isNullOrBlank()) return
        try { File(filePath).delete() } catch (_: Throwable) { }
    }
}
