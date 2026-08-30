package com.example.personalmemoryai.semantic

import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Text tower for the verified MobileCLIP-S2 TFLite model. */
class MobileClipTextEncoder(
    private val modelManager: MobileClipTextModelManager,
    private val tokenizer: OpenClipTokenizer
) : TextEncoder, AutoCloseable {
    companion object {
        const val MODEL_NAME = "MobileCLIP-S2"
        const val MODEL_VERSION = MobileClipTextModelManager.MODEL_VERSION
        const val OWNER_TYPE = "TEXT_QUERY"
        const val EMBEDDING_DIMENSION = 512
    }

    private var interpreter: Interpreter? = null

    @Synchronized
    override fun isReady(): Boolean = interpreter != null

    @Synchronized
    fun load(): Boolean {
        if (interpreter != null) return true
        if (!modelManager.isInstalled()) return false
        FileInputStream(modelManager.modelFile).channel.use { channel ->
            val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            interpreter = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(4) })
        }
        val tflite = interpreter ?: return false
        require(tflite.inputTensorCount == 1 && tflite.outputTensorCount == 1) {
            "MobileCLIP-S2 text tower must have one input and one output"
        }
        require(tflite.getInputTensor(0).dataType() == DataType.INT64) {
            "MobileCLIP-S2 text input must be INT64"
        }
        require(tflite.getInputTensor(0).shape().contentEquals(intArrayOf(1, OpenClipTokenizer.CONTEXT_LENGTH))) {
            "Unexpected MobileCLIP-S2 text input shape: ${tflite.getInputTensor(0).shape().contentToString()}"
        }
        require(tflite.getOutputTensor(0).dataType() == DataType.FLOAT32) {
            "MobileCLIP-S2 text output must be FLOAT32"
        }
        require(tflite.getOutputTensor(0).shape().fold(1) { a, b -> a * b } == EMBEDDING_DIMENSION) {
            "Unexpected MobileCLIP-S2 text output shape: ${tflite.getOutputTensor(0).shape().contentToString()}"
        }
        return true
    }

    override fun encode(text: String): FloatArray {
        check(load()) { "MobileCLIP-S2 text model is not installed" }
        require(text.isNotBlank()) { "Semantic text query must not be blank" }
        val inputIds = tokenizer.encode(text)
        val input = ByteBuffer.allocateDirect(inputIds.size * Long.SIZE_BYTES).order(ByteOrder.nativeOrder())
        inputIds.forEach(input::putLong)
        input.rewind()

        val output = ByteBuffer.allocateDirect(EMBEDDING_DIMENSION * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val started = System.nanoTime()
        interpreter!!.run(input, output)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        check(elapsedMs >= 0L)
        output.rewind()
        val vector = FloatArray(EMBEDDING_DIMENSION) { output.float }
        normalize(vector)
        require(vector.all { it.isFinite() }) { "MobileCLIP-S2 text encoder produced non-finite values" }
        return vector
    }

    fun tokenizerContractReport(): String =
        "context=${OpenClipTokenizer.CONTEXT_LENGTH}, vocab=49408, sot=${OpenClipTokenizer.SOT_ID}, eot=${OpenClipTokenizer.EOT_ID}"

    fun tokenIds(text: String): LongArray = tokenizer.encode(text)

    private fun normalize(vector: FloatArray) {
        var sum = 0.0
        for (v in vector) sum += v.toDouble() * v.toDouble()
        val norm = sqrt(sum)
        require(norm.isFinite() && norm > 0.0) { "MobileCLIP-S2 text embedding has zero norm" }
        for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
    }

    override fun close() {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
        }
    }
}
