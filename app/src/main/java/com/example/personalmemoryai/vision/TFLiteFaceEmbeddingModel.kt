package com.example.personalmemoryai.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generic TensorFlow Lite face embedding runner.
 *
 * IMPORTANT:
 * The preprocessing configuration must match the model that is actually
 * placed in assets.
 */
class TFLiteFaceEmbeddingModel(
    context: Context,
    private val modelFileName: String = DEFAULT_MODEL,
    private val preprocessing: Preprocessing = Preprocessing.NEGATIVE_ONE_TO_ONE
) : FaceEmbeddingModel {

    private val interpreter: Interpreter
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputChannels: Int
    private val inputType: DataType
    private val outputType: DataType
    private val outputDimension: Int

    override val modelName: String = modelFileName.substringBeforeLast(".")
    override val modelVersion: String = "tflite"
    override val embeddingDimension: Int get() = outputDimension

    init {
        val modelBuffer = ModelLoader.loadMappedModel(context = context, fileName = modelFileName)
        interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(DEFAULT_THREADS) })

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        require(inputShape.size == 4 && inputShape[0] == 1) {
            "Face embedding input must be 4D with batch=1. Actual shape: ${inputShape.contentToString()}"
        }
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        inputChannels = inputShape[3]
        require(inputChannels == 3) { "Only RGB models are supported. Channels: $inputChannels" }
        inputType = inputTensor.dataType()
        require(inputType == DataType.FLOAT32) { "MobileFaceNet input must be FLOAT32. Actual: $inputType" }

        val outputTensor = interpreter.getOutputTensor(0)
        outputType = outputTensor.dataType()
        require(outputType == DataType.FLOAT32) { "MobileFaceNet output must be FLOAT32. Actual: $outputType" }
        val outputShape = outputTensor.shape()
        outputDimension = outputShape.fold(1) { total, dimension -> total * dimension }
        require(outputDimension > 1) { "Invalid embedding dimension: $outputDimension" }
    }

    override suspend fun generateEmbedding(faceBitmap: Bitmap): FloatArray {
        require(!faceBitmap.isRecycled) { "Input face bitmap is recycled." }
        val resized = Bitmap.createScaledBitmap(faceBitmap, inputWidth, inputHeight, true)
        return try {
            val input = createInputBuffer(resized)
            val output = Array(1) { FloatArray(outputDimension) }
            interpreter.run(input, output)
            FaceSimilarity.normalize(output[0])
        } finally {
            if (resized !== faceBitmap && !resized.isRecycled) resized.recycle()
        }
    }

    private fun createInputBuffer(bitmap: Bitmap): ByteBuffer {
        val bytesPerValue = when (inputType) {
            DataType.FLOAT32 -> 4
            DataType.UINT8, DataType.INT8 -> 1
            else -> throw IllegalArgumentException("Unsupported input type: $inputType")
        }
        val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * inputChannels * bytesPerValue).order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        for (pixel in pixels) {
            putChannel(buffer, (pixel shr 16) and 0xFF)
            putChannel(buffer, (pixel shr 8) and 0xFF)
            putChannel(buffer, pixel and 0xFF)
        }
        buffer.rewind()
        return buffer
    }

    private fun putChannel(buffer: ByteBuffer, value: Int) {
        when (inputType) {
            DataType.FLOAT32 -> buffer.putFloat(
                when (preprocessing) {
                    Preprocessing.ZERO_TO_ONE -> value / 255f
                    Preprocessing.NEGATIVE_ONE_TO_ONE -> value / 127.5f - 1f
                    Preprocessing.RAW -> value.toFloat()
                }
            )
            DataType.UINT8 -> buffer.put(value.coerceIn(0, 255).toByte())
            DataType.INT8 -> buffer.put((value - 128).coerceIn(-128, 127).toByte())
            else -> error("Unsupported input type: $inputType")
        }
    }

    override fun close() = interpreter.close()

    enum class Preprocessing { ZERO_TO_ONE, NEGATIVE_ONE_TO_ONE, RAW }

    companion object {
        private const val DEFAULT_MODEL = "face_embedding.tflite"
        private const val DEFAULT_THREADS = 4
    }
}
