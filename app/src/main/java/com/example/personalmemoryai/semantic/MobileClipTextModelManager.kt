package com.example.personalmemoryai.semantic

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.diagnostics.ModelHealthReporter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Owns the optional on-device MobileCLIP-S2 text tower. */
class MobileClipTextModelManager(private val context: Context) {
    companion object {
        const val MODEL_FILE_NAME = "mobileclip_s2_text.tflite"
        const val MODEL_VERSION = "mobileclip-s2-text-tflite-v1"
        private const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
        const val CONTEXT_LENGTH = 77
        const val EMBEDDING_DIMENSION = 512
    }

    private val modelDir: File get() = File(context.filesDir, "models/semantic")
    val modelFile: File get() = File(modelDir, MODEL_FILE_NAME)
    private val diagnostics = DiagnosticsManager.get(context)
    private val health = ModelHealthReporter(context)

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() >= MIN_MODEL_BYTES
    fun installedSizeBytes(): Long = if (modelFile.isFile) modelFile.length() else 0L

    suspend fun importModel(source: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            val run = diagnostics.begin("TEXT_MODEL_IMPORT", mapOf("model" to MODEL_FILE_NAME, "source" to source.toString()))
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
                } ?: throw IllegalStateException("تعذر فتح ملف Text TFLite")
                require(temp.length() >= MIN_MODEL_BYTES) { "ملف Text TFLite غير مكتمل أو غير صحيح" }
                validateTflite(temp)
                if (modelFile.exists()) modelFile.delete()
                if (!temp.renameTo(modelFile)) {
                    temp.copyTo(modelFile, overwrite = true)
                    temp.delete()
                }
                run.success("MobileCLIP-S2 text tower imported and validated", mapOf("bytes" to modelFile.length().toString()))
                modelFile
            } catch (t: Throwable) {
                health.loadFailure(MODEL_FILE_NAME, t, mapOf("operation" to "IMPORT"))
                run.failure("IMPORT", t)
                throw t
            } finally {
                if (temp.exists()) temp.delete()
            }
        }

    private fun validateTflite(file: File) {
        FileInputStream(file).channel.use { channel ->
            val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            Interpreter(mapped).use { interpreter ->
                require(interpreter.inputTensorCount >= 1) { "Text TFLite has no input" }
                require(interpreter.outputTensorCount >= 1) { "Text TFLite has no output" }
                val input = interpreter.getInputTensor(0)
                require(input.dataType() == DataType.INT64) { "Text TFLite input must be INT64, got ${input.dataType()}" }
                require(input.shape().contentEquals(intArrayOf(1, CONTEXT_LENGTH))) { "Unexpected Text TFLite input: ${input.shape().contentToString()}" }
                val output = interpreter.getOutputTensor(0)
                require(output.dataType() == DataType.FLOAT32) { "Text TFLite output must be FLOAT32, got ${output.dataType()}" }
                require(output.shape().fold(1) { a, b -> a * b } == EMBEDDING_DIMENSION) { "Unexpected Text TFLite output: ${output.shape().contentToString()}" }
                val inputBuffer = ByteBuffer.allocateDirect(CONTEXT_LENGTH * 8).order(ByteOrder.nativeOrder())
                repeat(CONTEXT_LENGTH) { inputBuffer.putLong(if (it == 0) OpenClipTokenizer.SOT_ID.toLong() else OpenClipTokenizer.EOT_ID.toLong()) }
                inputBuffer.rewind()
                val outputBuffer = ByteBuffer.allocateDirect(EMBEDDING_DIMENSION * 4).order(ByteOrder.nativeOrder())
                interpreter.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()
                var finite = true
                var nonZero = false
                repeat(EMBEDDING_DIMENSION) {
                    val v = outputBuffer.float
                    if (!v.isFinite()) finite = false
                    if (v != 0f) nonZero = true
                }
                require(finite) { "Text TFLite health-check produced NaN/Infinity" }
                require(nonZero) { "Text TFLite health-check produced all-zero output" }
                health.loaded(MODEL_FILE_NAME, input.shape().contentToString(), output.shape().contentToString(), mapOf("version" to MODEL_VERSION))
            }
        }
    }

    fun deleteModel() {
        val run = diagnostics.begin("TEXT_MODEL_DELETE", mapOf("model" to MODEL_FILE_NAME))
        try {
            if (modelFile.exists()) modelFile.delete()
            run.success("Local text model deleted")
        } catch (t: Throwable) {
            run.failure("DELETE", t)
            throw t
        }
    }
}
