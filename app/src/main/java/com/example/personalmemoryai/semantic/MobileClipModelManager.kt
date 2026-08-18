package com.example.personalmemoryai.semantic

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the optional on-device MobileCLIP-S2 FP16 model.
 *
 * The model is deliberately NOT downloaded from the internet. The user imports
 * the .tflite file from device storage once; the app copies it into private
 * storage and reuses that copy on every subsequent launch.
 */
class MobileClipModelManager(private val context: Context) {

    companion object {
        const val MODEL_FILE_NAME = "mobileclip_s2_fp16.tflite"
        const val MODEL_VERSION = "mobileclip-s2-fp16-v1"
        private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L
    }

    private val modelDir: File
        get() = File(context.filesDir, "models/semantic")

    val modelFile: File
        get() = File(modelDir, MODEL_FILE_NAME)

    fun isInstalled(): Boolean =
        modelFile.isFile && modelFile.length() >= MIN_MODEL_BYTES

    fun installedSizeBytes(): Long = if (modelFile.isFile) modelFile.length() else 0L

    /** Import the model selected by Android's Storage Access Framework. */
    suspend fun importModel(source: Uri, onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            modelDir.mkdirs()
            val temp = File(modelDir, "$MODEL_FILE_NAME.importing")
            val resolver = context.contentResolver
            val total = resolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L

            try {
                resolver.openInputStream(source)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied, total)
                        }
                        output.fd.sync()
                    }
                } ?: throw IllegalStateException("تعذر فتح ملف النموذج")

                if (temp.length() < MIN_MODEL_BYTES) {
                    throw IllegalStateException("ملف MobileCLIP-S2 غير مكتمل أو ليس النموذج الصحيح")
                }

                if (modelFile.exists()) modelFile.delete()
                if (!temp.renameTo(modelFile)) {
                    temp.copyTo(modelFile, overwrite = true)
                    temp.delete()
                }
                modelFile
            } finally {
                if (temp.exists()) temp.delete()
            }
        }

    fun deleteModel() {
        if (modelFile.exists()) modelFile.delete()
    }
}
