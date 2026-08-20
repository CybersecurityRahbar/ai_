package com.example.personalmemoryai.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** TFLite face embedding runner for models stored in private app filesDir. */
class FileTFLiteFaceEmbeddingModel(
    context: Context,
    private val file: java.io.File,
    private val preprocessing: Preprocessing = Preprocessing.ZERO_TO_ONE,
    private val expectedDimension: Int? = null
) : FaceEmbeddingModel {
    private val interpreter: Interpreter
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputType: DataType
    private val outputDimension: Int
    private val contentHash: String

    override val modelName: String = file.nameWithoutExtension
    override val modelVersion: String
        get() = "file-tflite-${contentHash.take(16)}"
    override val embeddingDimension: Int get() = outputDimension

    init {
        require(file.isFile) { "Face model file does not exist: ${file.absolutePath}" }
        contentHash = sha256(file)
        val mapped = FileInputStream(file).channel.use { channel -> channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size()) }
        interpreter = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(4) })
        val input = interpreter.getInputTensor(0)
        val shape = input.shape()
        require(shape.size == 4 && shape[0] == 1 && shape[3] == 3) { "Face model input must be [1,H,W,3]: ${shape.contentToString()}" }
        inputHeight = shape[1]
        inputWidth = shape[2]
        inputType = input.dataType()
        require(inputType == DataType.FLOAT32) { "Only FLOAT32 face models are supported: $inputType" }
        val output = interpreter.getOutputTensor(0)
        outputDimension = output.shape().fold(1) { acc, value -> acc * value }
        require(output.dataType() == DataType.FLOAT32) { "Only FLOAT32 face model output is supported" }
        expectedDimension?.let { require(outputDimension == it) { "Expected $it-D embedding, got $outputDimension-D" } }
    }

    override suspend fun generateEmbedding(faceBitmap: Bitmap): FloatArray {
        require(!faceBitmap.isRecycled) { "Input face bitmap is recycled" }
        val resized = Bitmap.createScaledBitmap(faceBitmap, inputWidth, inputHeight, true)
        return try {
            val input = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4).order(ByteOrder.nativeOrder())
            val pixels = IntArray(inputWidth * inputHeight)
            resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            for (pixel in pixels) {
                put(input, (pixel shr 16) and 0xFF); put(input, (pixel shr 8) and 0xFF); put(input, pixel and 0xFF)
            }
            input.rewind()
            val output = Array(1) { FloatArray(outputDimension) }
            interpreter.run(input, output)
            FaceSimilarity.normalize(output[0])
        } finally {
            if (resized !== faceBitmap && !resized.isRecycled) resized.recycle()
        }
    }

    private fun put(buffer: ByteBuffer, value: Int) {
        buffer.putFloat(when (preprocessing) {
            Preprocessing.ZERO_TO_ONE -> value / 255f
            Preprocessing.NEGATIVE_ONE_TO_ONE -> value / 127.5f - 1f
            Preprocessing.RAW -> value.toFloat()
        })
    }

    override fun close() = interpreter.close()

    enum class Preprocessing { ZERO_TO_ONE, NEGATIVE_ONE_TO_ONE, RAW }

    companion object {
        private fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
