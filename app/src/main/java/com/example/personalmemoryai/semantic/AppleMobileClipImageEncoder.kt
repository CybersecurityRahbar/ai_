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
        const val IMAGE_RESOLUTION = 256
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
        require(loaded.inputTensorCount == 1 && loaded.outputTensorCount >= 1) {
            "Unexpected MobileCLIP-S2 image tensor counts"
        }
        require(loaded.getInputTensor(0).dataType() == DataType.FLOAT32) {
            "MobileCLIP-S2 image input must be FLOAT32"
        }
        require(loaded.getOutputTensor(0).dataType() == DataType.FLOAT32) {
            "MobileCLIP-S2 image output must be FLOAT32"
        }
        return true
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
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode image: $uri" }
        }
        return try { encode(bitmap) } finally { bitmap.recycle() }
    }

    fun encode(bitmap: Bitmap): FloatArray {
        check(load())
        val tflite = interpreter!!
        val shape = tflite.getInputTensor(0).shape()
        val channelsLast = shape.size == 4 && shape[0] == 1 && shape[3] == 3
        val channelsFirst = shape.size == 4 && shape[0] == 1 && shape[1] == 3
        require(channelsLast || channelsFirst) { "Unsupported MobileCLIP input shape: ${shape.contentToString()}" }
        val height = if (channelsLast) shape[1] else shape[2]
        val width = if (channelsLast) shape[2] else shape[3]
        require(height == IMAGE_RESOLUTION && width == IMAGE_RESOLUTION) { "Expected 256x256 image input, got ${width}x$height" }

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
                    when (c) { 0 -> ((p ushr 16) and 0xFF) / 255f; 1 -> ((p ushr 8) and 0xFF) / 255f; else -> (p and 0xFF) / 255f }
                )
            }
            input.rewind()
            val outElements = tflite.getOutputTensor(0).numElements()
            require(outElements == 512) { "Expected 512-D MobileCLIP output, got $outElements" }
            val output = ByteBuffer.allocateDirect(outElements * 4).order(ByteOrder.nativeOrder())
            tflite.run(input, output)
            output.rewind()
            val vector = FloatArray(outElements) { output.float }
            normalizeInPlace(vector)
            require(vector.all(Float::isFinite)) { "MobileCLIP image embedding is non-finite" }
            return vector
        } finally { cropped.recycle() }
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
