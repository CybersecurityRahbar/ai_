package com.example.personalmemoryai.semantic

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.diagnostics.ModelHealthReporter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Owns the optional on-device MobileCLIP-S2 FP16 model imported by the user. */
class MobileClipModelManager(private val context: Context) {
    companion object {
        const val MODEL_FILE_NAME = "mobileclip_s2_fp16.tflite"
        const val MODEL_VERSION = "mobileclip-s2-fp16-v1"
        private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L
    }
    private val modelDir: File get() = File(context.filesDir, "models/semantic")
    val modelFile: File get() = File(modelDir, MODEL_FILE_NAME)
    private val diagnostics = DiagnosticsManager.get(context)
    private val health = ModelHealthReporter(context)
    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() >= MIN_MODEL_BYTES
    fun installedSizeBytes(): Long = if (modelFile.isFile) modelFile.length() else 0L

    suspend fun importModel(source: Uri, onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("MODEL_IMPORT", mapOf("model" to MODEL_FILE_NAME, "source" to source.toString()))
        modelDir.mkdirs()
        val temp = File(modelDir, "$MODEL_FILE_NAME.importing")
        val resolver = context.contentResolver
        val total = resolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
        try {
            run.stage("COPY", "Copying selected model into private storage", mapOf("totalBytes" to total.toString()))
            resolver.openInputStream(source)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1024 * 1024); var copied = 0L
                    while (true) { val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read); copied += read; onProgress(copied, total) }
                    output.fd.sync()
                }
            } ?: throw IllegalStateException("تعذر فتح ملف النموذج")
            run.stage("VALIDATE_SIZE", "Checking model file size", mapOf("bytes" to temp.length().toString()))
            require(temp.length() >= MIN_MODEL_BYTES) { "ملف MobileCLIP-S2 غير مكتمل أو ليس النموذج الصحيح" }
            run.stage("VALIDATE_TFLITE", "Validating tensors and executing runtime health-check")
            validateTflite(temp)
            if (modelFile.exists()) modelFile.delete()
            if (!temp.renameTo(modelFile)) { temp.copyTo(modelFile, overwrite = true); temp.delete() }
            run.success("MobileCLIP-S2 imported, validated and inference-tested", mapOf("bytes" to modelFile.length().toString()))
            modelFile
        } catch (t: Throwable) {
            health.loadFailure(MODEL_FILE_NAME, t, mapOf("operation" to "IMPORT"))
            run.failure("IMPORT", t)
            throw t
        } finally { if (temp.exists()) temp.delete() }
    }

    private fun validateTflite(file: File) {
        try {
            FileInputStream(file).channel.use { channel ->
                val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                Interpreter(mapped).use { interpreter ->
                    require(interpreter.inputTensorCount >= 1) { "MobileCLIP-S2 لا يحتوي على مدخل صالح" }
                    require(interpreter.outputTensorCount >= 1) { "MobileCLIP-S2 لا يحتوي على مخرج صالح" }
                    val input = interpreter.getInputTensor(0); val shape = input.shape()
                    require(shape.size == 4 && shape[0] == 1) { "بنية إدخال MobileCLIP-S2 غير متوقعة: ${shape.contentToString()}" }
                    require(input.dataType() == DataType.FLOAT32) { "نوع إدخال MobileCLIP-S2 غير مدعوم: ${input.dataType()}" }
                    val output = interpreter.getOutputTensor(0)
                    require(output.dataType() == DataType.FLOAT32) { "نوع خرج MobileCLIP-S2 غير مدعوم: ${output.dataType()}" }
                    val inputElements = input.numElements(); val outputElements = output.numElements()
                    require(inputElements > 0 && outputElements > 0) { "MobileCLIP-S2 يحتوي على Tensor فارغ" }
                    val inputBuffer = java.nio.ByteBuffer.allocateDirect(inputElements * 4).order(java.nio.ByteOrder.nativeOrder())
                    repeat(inputElements) { inputBuffer.putFloat(0f) }; inputBuffer.rewind()
                    val outputBuffer = java.nio.ByteBuffer.allocateDirect(outputElements * 4).order(java.nio.ByteOrder.nativeOrder())
                    val started = System.nanoTime(); interpreter.run(inputBuffer, outputBuffer); val latencyMs = (System.nanoTime() - started) / 1_000_000L
                    outputBuffer.rewind(); var finite = true; var nonZero = false
                    repeat(outputElements) { val value = outputBuffer.float; if (!value.isFinite()) finite = false; if (value != 0f) nonZero = true }
                    require(finite) { "MobileCLIP-S2 health-check produced NaN/Infinity" }
                    require(nonZero) { "MobileCLIP-S2 health-check produced an all-zero output" }
                    health.loaded(MODEL_FILE_NAME, shape.contentToString(), output.shape().contentToString(), mapOf("version" to MODEL_VERSION))
                    health.inferenceSuccess(MODEL_FILE_NAME, latencyMs, outputElements, mapOf("inputShape" to shape.contentToString(), "outputShape" to output.shape().contentToString()))
                }
            }
        } catch (e: Exception) {
            health.tensorContractFailure(MODEL_FILE_NAME, e.message ?: e.javaClass.simpleName)
            throw IllegalStateException("ملف MobileCLIP-S2 غير صالح أو غير متوافق مع محرك التطبيق", e)
        }
    }

    fun deleteModel() {
        val run = diagnostics.begin("MODEL_DELETE", mapOf("model" to MODEL_FILE_NAME))
        try { if (modelFile.exists()) modelFile.delete(); run.success("Local model deleted") }
        catch (t: Throwable) { run.failure("DELETE", t); throw t }
    }
}
