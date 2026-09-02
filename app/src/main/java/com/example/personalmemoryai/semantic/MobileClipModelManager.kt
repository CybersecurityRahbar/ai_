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

/** Owns the optional on-device MobileCLIP-S2 image tower imported by the user. */
class MobileClipModelManager(private val context: Context) {
    companion object {
        const val MODEL_FILE_NAME = "mobileclip_s2_image.tflite"
        const val LEGACY_MODEL_FILE_NAME = "mobileclip_s2_fp16.tflite"
        const val MODEL_VERSION = "mobileclip-s2-image-tflite-v1"
        const val INPUT_CHANNELS = 3
        const val INPUT_RESOLUTION = 256
        const val OUTPUT_DIMENSION = 512
        private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L
    }

    private val modelDir: File get() = File(context.filesDir, "models/semantic")
    private val canonicalFile: File get() = File(modelDir, MODEL_FILE_NAME)
    private val legacyFile: File get() = File(modelDir, LEGACY_MODEL_FILE_NAME)
    val modelFile: File get() = if (canonicalFile.isFile) canonicalFile else legacyFile
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
            run.stage("COPY", "Copying selected MobileCLIP image model into private storage", mapOf("totalBytes" to total.toString()))
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
            } ?: throw IllegalStateException("تعذر فتح ملف Image TFLite")
            require(temp.length() >= MIN_MODEL_BYTES) { "ملف Image TFLite غير مكتمل أو غير صحيح" }
            validateTflite(temp)
            if (canonicalFile.exists()) canonicalFile.delete()
            if (!temp.renameTo(canonicalFile)) {
                temp.copyTo(canonicalFile, overwrite = true)
                temp.delete()
            }
            run.success("MobileCLIP-S2 image tower imported, validated and inference-tested", mapOf("bytes" to canonicalFile.length().toString()))
            canonicalFile
        } catch (t: Throwable) {
            health.loadFailure(MODEL_FILE_NAME, t, mapOf("operation" to "IMPORT"))
            run.failure("IMPORT", t)
            throw t
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun validateTflite(file: File) {
        try {
            FileInputStream(file).channel.use { channel ->
                val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                Interpreter(mapped).use { interpreter ->
                    require(interpreter.inputTensorCount == 1) { "Image TFLite must expose exactly one input tensor" }
                    require(interpreter.outputTensorCount >= 1) { "Image TFLite لا يحتوي على مخرج صالح" }
                    val input = interpreter.getInputTensor(0)
                    val shape = input.shape()
                    val channelsLast = shape.contentEquals(intArrayOf(1, INPUT_RESOLUTION, INPUT_RESOLUTION, INPUT_CHANNELS))
                    val channelsFirst = shape.contentEquals(intArrayOf(1, INPUT_CHANNELS, INPUT_RESOLUTION, INPUT_RESOLUTION))
                    require(channelsLast || channelsFirst) {
                        "بنية Image TFLite غير متوافقة مع MobileCLIP-S2: ${shape.contentToString()}"
                    }
                    require(input.dataType() == DataType.FLOAT32) { "نوع إدخال Image TFLite غير مدعوم: ${input.dataType()}" }
                    val output = interpreter.getOutputTensor(0)
                    require(output.dataType() == DataType.FLOAT32) { "نوع خرج Image TFLite غير مدعوم: ${output.dataType()}" }
                    require(output.numElements() == OUTPUT_DIMENSION) {
                        "MobileCLIP-S2 image output must be ${OUTPUT_DIMENSION}-D, got ${output.shape().contentToString()}"
                    }
                    val inputBuffer = java.nio.ByteBuffer.allocateDirect(input.numElements() * 4).order(java.nio.ByteOrder.nativeOrder())
                    repeat(input.numElements()) { inputBuffer.putFloat(0f) }
                    inputBuffer.rewind()
                    val outputBuffer = java.nio.ByteBuffer.allocateDirect(output.numElements() * 4).order(java.nio.ByteOrder.nativeOrder())
                    interpreter.run(inputBuffer, outputBuffer)
                    outputBuffer.rewind()
                    var finite = true
                    var nonZero = false
                    repeat(output.numElements()) {
                        val value = outputBuffer.float
                        if (!value.isFinite()) finite = false
                        if (value != 0f) nonZero = true
                    }
                    require(finite) { "Image TFLite health-check produced NaN/Infinity" }
                    require(nonZero) { "Image TFLite health-check produced all-zero output" }
                    health.loaded(MODEL_FILE_NAME, shape.contentToString(), output.shape().contentToString(), mapOf("version" to MODEL_VERSION))
                    health.inferenceSuccess(MODEL_FILE_NAME, 0L, output.numElements(), mapOf("inputShape" to shape.contentToString(), "outputShape" to output.shape().contentToString()))
                }
            }
        } catch (e: Exception) {
            health.tensorContractFailure(MODEL_FILE_NAME, e.message ?: e.javaClass.simpleName)
            throw IllegalStateException("ملف Image TFLite غير صالح أو غير متوافق مع محرك التطبيق", e)
        }
    }

    fun deleteModel() {
        val run = diagnostics.begin("MODEL_DELETE", mapOf("model" to MODEL_FILE_NAME))
        try {
            if (canonicalFile.exists()) canonicalFile.delete()
            if (legacyFile.exists()) legacyFile.delete()
            run.success("Local MobileCLIP image model deleted")
        } catch (t: Throwable) {
            run.failure("DELETE", t)
            throw t
        }
    }
}
