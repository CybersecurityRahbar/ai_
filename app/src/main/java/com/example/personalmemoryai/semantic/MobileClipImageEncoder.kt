package com.example.personalmemoryai.semantic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * MobileCLIP-S2 image encoder.
 *
 * This class deliberately exposes image embeddings only. Text encoding is
 * kept behind a separate interface so a compatible Text Encoder can be added
 * later without changing the image index format.
 */
class MobileClipImageEncoder(
    private val context: Context,
    private val modelManager: MobileClipModelManager
) : AutoCloseable {

    companion object {
        const val MODEL_NAME = "MobileCLIP-S2"
        const val MODEL_VERSION = MobileClipModelManager.MODEL_VERSION
        const val OWNER_TYPE = "IMAGE"
    }

    private var interpreter: Interpreter? = null

    @Synchronized
    fun isReady(): Boolean = interpreter != null

    @Synchronized
    fun load(): Boolean {
        if (interpreter != null) return true
        if (!modelManager.isInstalled()) return false

        val file = modelManager.modelFile
        FileInputStream(file).channel.use { channel ->
            val mapped = channel.map(
                java.nio.channels.FileChannel.MapMode.READ_ONLY,
                0,
                channel.size()
            )
            interpreter = Interpreter(
                mapped,
                Interpreter.Options().apply { setNumThreads(4) }
            )
        }
        return true
    }

    fun encode(uri: Uri): FloatArray {
        check(load()) { "MobileCLIP-S2 model is not installed" }

        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode image: $uri" }
        }

        return try {
            encode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun encode(bitmap: Bitmap): FloatArray {
        val tflite = interpreter ?: error("MobileCLIP-S2 interpreter is not loaded")
        val inputShape = tflite.getInputTensor(0).shape()
        require(inputShape.size == 4) { "Unexpected MobileCLIP input shape: ${inputShape.contentToString()}" }

        val channelsLast = inputShape[3] == 3
        val height = if (channelsLast) inputShape[1] else inputShape[2]
        val width = if (channelsLast) inputShape[2] else inputShape[3]
        require(inputShape[0] == 1) { "Only batch size 1 is supported" }

        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        resized.getPixels(pixels, 0, width, 0, 0, width, height)
        resized.recycle()

        val input = FloatArray(width * height * 3)
        // MobileCLIP-S2 uses the v1/S2 preprocessing: RGB converted to [0,1]
        // without ImageNet/CLIP mean-std normalization.
        if (channelsLast) {
            var p = 0
            for (pixel in pixels) {
                input[p++] = ((pixel shr 16) and 0xFF) / 255f
                input[p++] = ((pixel shr 8) and 0xFF) / 255f
                input[p++] = (pixel and 0xFF) / 255f
            }
        } else {
            var p = 0
            for (c in 0..2) {
                for (pixel in pixels) {
                    input[p++] = when (c) {
                        0 -> ((pixel shr 16) and 0xFF) / 255f
                        1 -> ((pixel shr 8) and 0xFF) / 255f
                        else -> (pixel and 0xFF) / 255f
                    }
                }
            }
        }

        val inputBuffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        input.forEach(inputBuffer::putFloat)
        inputBuffer.rewind()

        val outputTensor = tflite.getOutputTensor(0)
        val outputElements = outputTensor.shape().fold(1) { a, b -> a * b }
        val output = Array(1) { FloatArray(outputElements) }
        tflite.run(inputBuffer, output)

        val embedding = output[0]
        normalizeInPlace(embedding)
        return embedding
    }

    private fun normalizeInPlace(vector: FloatArray) {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value.toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) vector[i] /= norm
        }
    }

    override fun close() {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
        }
    }
}
