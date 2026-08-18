package com.example.personalmemoryai.semantic

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter

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

                validateTflite(temp)

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

    /**
     * Performs a lightweight structural validation before the model becomes
     * the active persistent model. This catches corrupt files and unrelated
     * TFLite models instead of letting them fail later during indexing.
     */
    private fun validateTflite(file: File) {
        try {
            FileInputStream(file).channel.use { channel ->
                val mapped = channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    0,
                    channel.size()
                )
                Interpreter(mapped).use { interpreter ->
                    require(interpreter.inputTensorCount >= 1) {
                        "MobileCLIP-S2 لا يحتوي على مدخل صالح"
                    }
                    require(interpreter.outputTensorCount >= 1) {
                        "MobileCLIP-S2 لا يحتوي على مخرج صالح"
                    }

                    val input = interpreter.getInputTensor(0)
                    val shape = input.shape()
                    require(shape.size == 4 && shape[0] == 1) {
                        "بنية إدخال MobileCLIP-S2 غير متوقعة: ${shape.contentToString()}"
                    }
                    require(input.dataType() == org.tensorflow.lite.DataType.FLOAT32) {
                        "نوع إدخال MobileCLIP-S2 غير مدعوم: ${input.dataType()}"
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("ملف MobileCLIP-S2 غير صالح أو غير متوافق مع محرك التطبيق", e)
        }
    }

    fun deleteModel() {
        if (modelFile.exists()) modelFile.delete()
    }
}
