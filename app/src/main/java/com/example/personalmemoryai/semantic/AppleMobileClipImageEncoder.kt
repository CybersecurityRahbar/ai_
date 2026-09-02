package com.example.personalmemoryai.semantic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * MobileCLIP-S2 image tower with the exact preprocessing contract used by the
 * pinned Apple/OpenCLIP reference: shortest-side resize to 256, center crop
 * to 256x256, RGB float32 in [0,1], mean=0/std=1.
 */
class AppleMobileClipImageEncoder(
    private val context: Context,
    private val modelManager: MobileClipModelManager
) : AutoCloseable {
    companion object {
        const val MODEL_NAME = "MobileCLIP-S2"
        const val MODEL_VERSION = MobileClipModelManager.MODEL_VERSION
        const val OWNER_TYPE = "IMAGE"
        const val IMAGE_RESOLUTION = MobileClipModelManager.INPUT_RESOLUTION
        const val EMBEDDING_DIMENSION = MobileClipModelManager.OUTPUT_DIMENSION
    }

    private var interpreter: Interpreter? = null

    @Synchronized
    fun load(): Boolean {
        if (interpreter != null) return true
        if (!modelManager.isInstalled()) return false
        FileInputStream(modelManager.modelFile).channel.use { channel ->
            val mapped = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
            interpreter = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(4) })
        }
        val loaded = interpreter ?: return false
        try {
            require(loaded.inputTensorCount == 1 && loaded.outputTensorCount >= 1) {
                "Unexpected MobileCLIP-S2 image tensor counts"
            }
            val input = loaded.getInputTensor(0)
            val shape = input.shape()
            require(
                shape.contentEquals(intArrayOf(1, IMAGE_RESOLUTION, IMAGE_RESOLUTION, 3)) ||
                    shape.contentEquals(intArrayOf(1, 3, IMAGE_RESOLUTION, IMAGE_RESOLUTION))
            ) { "Unsupported MobileCLIP-S2 image input shape: ${shape.contentToString()}" }
            require(input.dataType() == DataType.FLOAT32) {
                "MobileCLIP-S2 image input must be FLOAT32"
            }
            val output = loaded.getOutputTensor(0)
            require(output.dataType() == DataType.FLOAT32) {
                "MobileCLIP-S2 image output must be FLOAT32"
            }
            require(output.numElements() == EMBEDDING_DIMENSION) {
                "MobileCLIP-S2 image output must be ${EMBEDDING_DIMENSION}-D"
            }
            return true
        } catch (t: Throwable) {
            loaded.close()
            interpreter = null
            throw t
        }
    }

    fun tensorReport(): String {
        check(load())
        val t = interpreter!!
        val input = t.getInputTensor(0)
        val output = t.getOutputTensor(0)
        return "INPUT: ${input.name()} ${input.dataType()} ${input.shape().contentToString()}\nOUTPUT: ${output.name()} ${output.dataType()} ${output.shape().contentToString()}"
    }

    fun modelInputShape(): IntArray { check(load()); return interpreter!!.getInputTensor(0).shape().clone() }
    fun modelOutputShape(): IntArray { check(load()); return interpreter!!.getOutputTensor(0).shape().clone() }

    fun encode(uri: Uri): FloatArray {
        check(load()) { "MobileCLIP-S2 image model is not installed" }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IllegalStateException("Unable to decode or open image: $uri")
        return try {
            require(bitmap.width > 0 && bitmap.height > 0) { "Decoded image has invalid dimensions: $uri" }
            encode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun encode(bitmap: Bitmap): FloatArray {
        check(load())
        val tflite = interpreter!!
        val shape = tflite.getInputTensor(0).shape()
        val channelsLast = shape.contentEquals(intArrayOf(1, IMAGE_RESOLUTION, IMAGE_RESOLUTION, 3))
        val channelsFirst = shape.contentEquals(intArrayOf(1, 3, IMAGE_RESOLUTION, IMAGE_RESOLUTION))
        require(channelsLast || channelsFirst) { "Unsupported MobileCLIP input shape: ${shape.contentToString()}" }
        val height = IMAGE_RESOLUTION
        val width = IMAGE_RESOLUTION

        val cropped = resizeShortestSideAndCenterCrop(bitmap, width)
        try {
            val pixels = IntArray(width * height)
            cropped.getPixels(pixels, 0, width, 0, 0, width, height)
            val input = ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder())
            if (channelsLast) {
                for (p in pixels) {
                    input.putFloat(((p ushr 16) and 0xFF) / 255f)
                    input.putFloat(((p ushr 8) and 0xFF) / 255f)
                    input.putFloat((p and 0xFF) / 255f)
                }
            } else {
                for (c in 0..2) for (p in pixels) input.putFloat(
                    when (c) {
                        0 -> ((p ushr 16) and 0xFF) / 255f
                        1 -> ((p ushr 8) and 0xFF) / 255f
                        else -> (p and 0xFF) / 255f
                    }
                )
            }
            input.rewind()
            val outElements = tflite.getOutputTensor(0).numElements()
            require(outElements == EMBEDDING_DIMENSION) {
                "Expected ${EMBEDDING_DIMENSION}-D MobileCLIP output, got $outElements"
            }
            val output = ByteBuffer.allocateDirect(outElements * 4).order(ByteOrder.nativeOrder())
            tflite.run(input, output)
            output.rewind()
            val vector = FloatArray(outElements) { output.float }
            normalizeInPlace(vector)
            require(vector.all(Float::isFinite)) { "MobileCLIP image embedding is non-finite" }
            return vector
        } finally {
            cropped.recycle()
        }
    }

    private fun resizeShortestSideAndCenterCrop(bitmap: Bitmap, target: Int): Bitmap {
        val sourceW = bitmap.width
        val sourceH = bitmap.height
        require(sourceW > 0 && sourceH > 0)
        val scale = target.toFloat() / minOf(sourceW, sourceH).toFloat()
        val resizedW = max(target, (sourceW * scale).roundToInt())
        val resizedH = max(target, (sourceH * scale).roundToInt())
        val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        if (resizedW == target && resizedH == target) return resized
        val left = ((resizedW - target) / 2).coerceAtLeast(0)
        val top = ((resizedH - target) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(resized, left, top, target, target).also { cropped -> if (cropped !== resized) resized.recycle() }
    }

    private fun normalizeInPlace(v: FloatArray) {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        val norm = sqrt(sum)
        require(norm.isFinite() && norm > 0.0) { "Zero-norm MobileCLIP image embedding" }
        for (i in v.indices) v[i] = (v[i] / norm).toFloat()
    }

    fun isReady(): Boolean = interpreter != null

    override fun close() { synchronized(this) { interpreter?.close(); interpreter = null } }
}
