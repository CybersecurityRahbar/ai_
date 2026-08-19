package com.example.personalmemoryai.semantic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** MobileCLIP-S2 image encoder with tensor-safe IO and CLIP image normalization. */
class MobileClipImageEncoder(
    private val context: Context,
    private val modelManager: MobileClipModelManager
) : AutoCloseable {
    companion object {
        const val MODEL_NAME = "MobileCLIP-S2"
        const val MODEL_VERSION = MobileClipModelManager.MODEL_VERSION
        const val OWNER_TYPE = "IMAGE"
        private val CLIP_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val CLIP_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private var interpreter: Interpreter? = null

    @Synchronized fun isReady(): Boolean = interpreter != null

    @Synchronized
    fun load(): Boolean {
        if (interpreter != null) return true
        if (!modelManager.isInstalled()) return false
        val file = modelManager.modelFile
        FileInputStream(file).channel.use { channel ->
            val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            interpreter = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(4) })
        }
        val loaded = interpreter ?: return false
        require(loaded.inputTensorCount >= 1 && loaded.outputTensorCount >= 1) { "MobileCLIP-S2 model has no usable input/output tensors" }
        require(loaded.getInputTensor(0).dataType() == DataType.FLOAT32) { "MobileCLIP-S2 input tensor must accept FLOAT32" }
        require(loaded.getOutputTensor(0).dataType() == DataType.FLOAT32) { "MobileCLIP-S2 output tensor must be FLOAT32" }
        return true
    }

    fun encode(uri: Uri): FloatArray {
        check(load()) { "MobileCLIP-S2 model is not installed" }
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode image: $uri" }
        }
        return try { encode(bitmap) } finally { bitmap.recycle() }
    }

    fun encode(bitmap: Bitmap): FloatArray {
        val tflite = interpreter ?: error("MobileCLIP-S2 interpreter is not loaded")
        val inputShape = tflite.getInputTensor(0).shape()
        require(inputShape.size == 4) { "Unexpected MobileCLIP input shape: ${inputShape.contentToString()}" }
        val channelsLast = inputShape[3] == 3
        val height = if (channelsLast) inputShape[1] else inputShape[2]
        val width = if (channelsLast) inputShape[2] else inputShape[3]
        require(inputShape[0] == 1 && height > 0 && width > 0) { "Unexpected MobileCLIP input shape: ${inputShape.contentToString()}" }

        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        resized.getPixels(pixels, 0, width, 0, 0, width, height)
        resized.recycle()
        val input = ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder())

        fun normalized(pixel: Int, channel: Int): Float {
            val value = when (channel) {
                0 -> ((pixel shr 16) and 0xFF) / 255f
                1 -> ((pixel shr 8) and 0xFF) / 255f
                else -> (pixel and 0xFF) / 255f
            }
            return (value - CLIP_MEAN[channel]) / CLIP_STD[channel]
        }

        if (channelsLast) {
            for (pixel in pixels) {
                input.putFloat(normalized(pixel, 0))
                input.putFloat(normalized(pixel, 1))
                input.putFloat(normalized(pixel, 2))
            }
        } else {
            for (channel in 0..2) for (pixel in pixels) input.putFloat(normalized(pixel, channel))
        }
        input.rewind()

        val outputTensor = tflite.getOutputTensor(0)
        val outputElements = outputTensor.shape().fold(1) { a, b -> a * b }
        require(outputElements > 0) { "MobileCLIP-S2 output tensor is empty" }
        val output = ByteBuffer.allocateDirect(outputElements * 4).order(ByteOrder.nativeOrder())
        tflite.run(input, output)
        output.rewind()
        val embedding = FloatArray(outputElements) { output.float }
        normalizeInPlace(embedding)
        require(embedding.all { it.isFinite() }) { "MobileCLIP-S2 produced non-finite embedding values" }
        require(embedding.any { it != 0f }) { "MobileCLIP-S2 produced an empty embedding" }
        return embedding
    }

    private fun normalizeInPlace(vector: FloatArray) {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value.toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm > 0f) for (i in vector.indices) vector[i] /= norm
    }

    override fun close() { synchronized(this) { interpreter?.close(); interpreter = null } }
}
