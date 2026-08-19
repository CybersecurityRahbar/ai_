package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.personalmemoryai.vision.ModelLoader
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** YOLO LiteRT/TFLite detector with support for end-to-end and raw YOLO outputs. */
class YoloObjectDetector(private val context: Context) : AutoCloseable {
    companion object {
        private const val MODEL_PATH = "models/object/yolo26n_w8a32.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.20f
        private const val NMS_IOU_THRESHOLD = 0.45f
        private const val MAX_RESULTS = 100
        private val COCO_LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }

    private var interpreter: Interpreter? = null

    @Synchronized
    private fun load(): Interpreter = interpreter ?: Interpreter(
        ModelLoader.loadMappedModel(context, MODEL_PATH),
        Interpreter.Options().apply { setNumThreads(4) }
    ).also { interpreter = it }

    fun detect(uri: Uri): List<ObjectDetectionResult> {
        val bitmap = context.contentResolver.openInputStream(uri).use { input -> requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode image: $uri" } }
        return try { detect(bitmap) } finally { bitmap.recycle() }
    }

    fun detect(bitmap: Bitmap): List<ObjectDetectionResult> {
        val tflite = load()
        val inputTensor = tflite.getInputTensor(0)
        val inputShape = inputTensor.shape()
        require(inputShape.size == 4 && inputShape[0] == 1) { "Unexpected YOLO input shape: ${inputShape.contentToString()}" }
        val channelsLast = inputShape[3] == 3
        val inputHeight = if (channelsLast) inputShape[1] else inputShape[2]
        val inputWidth = if (channelsLast) inputShape[2] else inputShape[3]
        require(inputWidth == inputHeight) { "Only square YOLO input is supported" }
        require(inputTensor.dataType() == DataType.FLOAT32) { "YOLO model expects FLOAT32 input, found ${inputTensor.dataType()}" }

        val scale = min(inputWidth.toFloat() / bitmap.width, inputHeight.toFloat() / bitmap.height)
        val resizedWidth = max(1, (bitmap.width * scale).toInt())
        val resizedHeight = max(1, (bitmap.height * scale).toInt())
        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val letterboxed = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(letterboxed)
        canvas.drawColor(android.graphics.Color.BLACK)
        val padLeft = (inputWidth - resizedWidth) / 2f
        val padTop = (inputHeight - resizedHeight) / 2f
        canvas.drawBitmap(resized, padLeft, padTop, null)
        resized.recycle()

        val pixels = IntArray(inputWidth * inputHeight)
        letterboxed.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        letterboxed.recycle()
        val input = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4).order(ByteOrder.nativeOrder())
        if (channelsLast) {
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16) and 0xFF) / 255f)
                input.putFloat(((pixel shr 8) and 0xFF) / 255f)
                input.putFloat((pixel and 0xFF) / 255f)
            }
        } else {
            for (channel in 0..2) for (pixel in pixels) input.putFloat(when (channel) {
                0 -> ((pixel shr 16) and 0xFF) / 255f
                1 -> ((pixel shr 8) and 0xFF) / 255f
                else -> (pixel and 0xFF) / 255f
            })
        }
        input.rewind()

        val outputTensor = tflite.getOutputTensor(0)
        require(outputTensor.dataType() == DataType.FLOAT32) { "Unsupported YOLO output type: ${outputTensor.dataType()}" }
        val outputElements = outputTensor.shape().fold(1) { a, b -> a * b }
        val output = ByteBuffer.allocateDirect(outputElements * 4).order(ByteOrder.nativeOrder())
        tflite.run(input, output)
        output.rewind()
        val values = FloatArray(outputElements) { output.float }
        return parseOutput(values, outputTensor.shape(), scale, padLeft, padTop)
    }

    private fun parseOutput(values: FloatArray, shape: IntArray, scale: Float, padLeft: Float, padTop: Float): List<ObjectDetectionResult> {
        if (shape.size == 3 && shape[0] == 1 && shape[2] == 6) return parseRows(values, shape[1], 6, scale, padLeft, padTop)
        if (shape.size == 3 && shape[0] == 1 && shape[1] == 6) {
            val count = shape[2]
            val rows = FloatArray(count * 6)
            for (i in 0 until count) for (c in 0..5) rows[i * 6 + c] = values[c * count + i]
            return parseRows(rows, count, 6, scale, padLeft, padTop)
        }

        // Standard YOLO detection-head export: [1, 84, N] or [1, N, 84].
        // 84 = cx,cy,w,h + 80 COCO class scores.
        if (shape.size == 3 && shape[0] == 1 && shape[1] == 84) {
            val count = shape[2]
            val rows = FloatArray(count * 84)
            for (i in 0 until count) for (c in 0 until 84) rows[i * 84 + c] = values[c * count + i]
            return parseRawRows(rows, count, 84, scale, padLeft, padTop)
        }
        if (shape.size == 3 && shape[0] == 1 && shape[2] == 84) {
            return parseRawRows(values, shape[1], 84, scale, padLeft, padTop)
        }
        throw IllegalStateException("Unsupported YOLO output shape: ${shape.contentToString()}")
    }

    private fun parseRows(values: FloatArray, count: Int, stride: Int, scale: Float, padLeft: Float, padTop: Float): List<ObjectDetectionResult> {
        val results = ArrayList<ObjectDetectionResult>(min(count, MAX_RESULTS))
        for (i in 0 until count) {
            val o = i * stride
            val confidence = values[o + 4]
            if (!confidence.isFinite() || confidence < CONFIDENCE_THRESHOLD) continue
            val classId = values[o + 5].toInt()
            if (classId !in COCO_LABELS.indices) continue
            val x1 = (values[o] - padLeft) / scale
            val y1 = (values[o + 1] - padTop) / scale
            val x2 = (values[o + 2] - padLeft) / scale
            val y2 = (values[o + 3] - padTop) / scale
            if (!listOf(x1, y1, x2, y2).all { it.isFinite() }) continue
            results += makeResult(classId, confidence, x1, y1, x2, y2)
            if (results.size >= MAX_RESULTS) break
        }
        return nms(results)
    }

    private fun parseRawRows(values: FloatArray, count: Int, stride: Int, scale: Float, padLeft: Float, padTop: Float): List<ObjectDetectionResult> {
        val candidates = ArrayList<ObjectDetectionResult>(min(count, MAX_RESULTS * 2))
        for (i in 0 until count) {
            val o = i * stride
            val cx = values[o]
            val cy = values[o + 1]
            val w = values[o + 2]
            val h = values[o + 3]
            if (!listOf(cx, cy, w, h).all { it.isFinite() } || w <= 0f || h <= 0f) continue
            var bestClass = -1
            var bestScore = 0f
            for (classId in COCO_LABELS.indices) {
                val raw = values[o + 4 + classId]
                val score = if (raw in 0f..1f) raw else sigmoid(raw)
                if (score > bestScore) { bestScore = score; bestClass = classId }
            }
            if (bestClass < 0 || bestScore < CONFIDENCE_THRESHOLD) continue
            val x1 = (cx - w / 2f - padLeft) / scale
            val y1 = (cy - h / 2f - padTop) / scale
            val x2 = (cx + w / 2f - padLeft) / scale
            val y2 = (cy + h / 2f - padTop) / scale
            if (!listOf(x1, y1, x2, y2).all { it.isFinite() }) continue
            candidates += makeResult(bestClass, bestScore, x1, y1, x2, y2)
        }
        return nms(candidates)
    }

    private fun makeResult(classId: Int, confidence: Float, x1: Float, y1: Float, x2: Float, y2: Float) =
        ObjectDetectionResult(classId, COCO_LABELS[classId], ObjectLabelCatalog.arabicAliases(COCO_LABELS[classId]), confidence, x1.coerceAtLeast(0f), y1.coerceAtLeast(0f), x2.coerceAtLeast(0f), y2.coerceAtLeast(0f))

    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x.toDouble()).toFloat())).coerceIn(0f, 1f)

    private fun nms(input: List<ObjectDetectionResult>): List<ObjectDetectionResult> {
        val remaining = input.sortedByDescending { it.confidence }.toMutableList()
        val kept = ArrayList<ObjectDetectionResult>()
        while (remaining.isNotEmpty() && kept.size < MAX_RESULTS) {
            val best = remaining.removeAt(0)
            kept += best
            remaining.removeAll { it.classId == best.classId && iou(best, it) >= NMS_IOU_THRESHOLD }
        }
        return kept
    }

    private fun iou(a: ObjectDetectionResult, b: ObjectDetectionResult): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() { synchronized(this) { interpreter?.close(); interpreter = null } }
}
