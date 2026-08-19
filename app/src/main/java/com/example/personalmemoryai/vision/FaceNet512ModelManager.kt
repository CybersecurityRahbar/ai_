package com.example.personalmemoryai.vision

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

/** Owns the optional user-imported FaceNet-512 TFLite model. */
class FaceNet512ModelManager(private val context: Context) {
    companion object {
        const val MODEL_FILE_NAME = "facenet_512.tflite"
        const val MODEL_NAME = "facenet_512"
        const val MODEL_VERSION = "facenet-512-v1"
        const val INPUT_WIDTH = 160
        const val INPUT_HEIGHT = 160
        const val EMBEDDING_DIMENSION = 512
        private const val MIN_MODEL_BYTES = 10L * 1024L * 1024L
    }

    private val modelDir: File get() = File(context.filesDir, "models/face")
    val modelFile: File get() = File(modelDir, MODEL_FILE_NAME)
    private val diagnostics = DiagnosticsManager.get(context)
    private val health = ModelHealthReporter(context)
    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() >= MIN_MODEL_BYTES
    fun installedSizeBytes(): Long = if (modelFile.isFile) modelFile.length() else 0L

    suspend fun importModel(source: Uri, onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("FACE_MODEL_IMPORT", mapOf("model" to MODEL_NAME, "source" to source.toString()))
        modelDir.mkdirs(); val temp = File(modelDir, "$MODEL_FILE_NAME.importing")
        try {
            val resolver = context.contentResolver; val total = resolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
            run.stage("COPY", "Copying FaceNet-512 into private app storage", mapOf("totalBytes" to total.toString()))
            resolver.openInputStream(source)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1024 * 1024); var copied = 0L
                    while (true) { val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read); copied += read; onProgress(copied, total) }
                    output.fd.sync()
                }
            } ?: error("تعذر فتح ملف FaceNet-512")
            require(temp.length() >= MIN_MODEL_BYTES) { "ملف FaceNet-512 غير مكتمل أو ليس النموذج الصحيح" }
            run.stage("VALIDATE_TFLITE", "Validating FaceNet-512 tensors and executing health-check inference")
            validateTflite(temp)
            if (modelFile.exists()) modelFile.delete()
            if (!temp.renameTo(modelFile)) { temp.copyTo(modelFile, overwrite = true); temp.delete() }
            run.success("FaceNet-512 imported, validated and inference-tested", mapOf("bytes" to modelFile.length().toString(), "dimension" to EMBEDDING_DIMENSION.toString()))
            modelFile
        } catch (t: Throwable) {
            health.loadFailure(MODEL_NAME, t, mapOf("operation" to "IMPORT")); run.failure("IMPORT", t); throw t
        } finally { if (temp.exists()) temp.delete() }
    }

    private fun validateTflite(file: File) {
        try {
            FileInputStream(file).channel.use { channel ->
                val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                Interpreter(mapped, Interpreter.Options().apply { setNumThreads(4) }).use { interpreter ->
                    require(interpreter.inputTensorCount >= 1) { "FaceNet-512 لا يحتوي على مدخل" }
                    require(interpreter.outputTensorCount >= 1) { "FaceNet-512 لا يحتوي على مخرج" }
                    val input = interpreter.getInputTensor(0)
                    require(input.shape().contentEquals(intArrayOf(1, INPUT_HEIGHT, INPUT_WIDTH, 3))) { "بنية إدخال FaceNet-512 غير متوقعة: ${input.shape().contentToString()}" }
                    require(input.dataType() == DataType.FLOAT32) { "نوع إدخال FaceNet-512 غير مدعوم: ${input.dataType()}" }
                    val output = interpreter.getOutputTensor(0); val outputDimension = output.shape().fold(1) { acc, value -> acc * value }
                    require(outputDimension == EMBEDDING_DIMENSION) { "بصمة FaceNet-512 يجب أن تكون 512، والنتيجة الحالية $outputDimension" }
                    require(output.dataType() == DataType.FLOAT32) { "نوع خرج FaceNet-512 غير مدعوم: ${output.dataType()}" }
                    val inputBuffer = java.nio.ByteBuffer.allocateDirect(INPUT_WIDTH * INPUT_HEIGHT * 3 * 4).order(java.nio.ByteOrder.nativeOrder())
                    repeat(INPUT_WIDTH * INPUT_HEIGHT * 3) { inputBuffer.putFloat(0f) }; inputBuffer.rewind()
                    val outputBuffer = Array(1) { FloatArray(EMBEDDING_DIMENSION) }
                    val started = System.nanoTime(); interpreter.run(inputBuffer, outputBuffer); val latencyMs = (System.nanoTime() - started) / 1_000_000L
                    require(outputBuffer[0].all { it.isFinite() }) { "FaceNet-512 health-check produced NaN/Infinity" }
                    require(outputBuffer[0].any { it != 0f }) { "FaceNet-512 health-check produced an all-zero embedding" }
                    health.loaded(MODEL_NAME, input.shape().contentToString(), output.shape().contentToString(), mapOf("version" to MODEL_VERSION))
                    health.inferenceSuccess(MODEL_NAME, latencyMs, EMBEDDING_DIMENSION, mapOf("inputShape" to input.shape().contentToString(), "outputShape" to output.shape().contentToString()))
                }
            }
        } catch (e: Exception) {
            health.tensorContractFailure(MODEL_NAME, e.message ?: e.javaClass.simpleName)
            throw IllegalStateException("ملف FaceNet-512 غير صالح أو غير متوافق مع محرك التطبيق", e)
        }
    }

    fun deleteModel() {
        val run = diagnostics.begin("FACE_MODEL_DELETE", mapOf("model" to MODEL_NAME))
        try { if (modelFile.exists()) modelFile.delete(); run.success("FaceNet-512 local model deleted") }
        catch (t: Throwable) { run.failure("DELETE", t); throw t }
    }
}
