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
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO26n LiteRT/TFLite detector exported by Ultralytics as w8a32.
 *
 * The current model is an end-to-end detection export, whose common output is
 * [x1, y1, x2, y2, confidence, classId] per detection. The parser also checks
 * tensor shape at runtime instead of hard-coding 300 detections.
 */
class YoloObjectDetector(private val context: Context) : AutoCloseable {

    companion object {
        private const val MODEL_PATH = "models/object/yolo26n_w8a32.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val MAX_RESULTS = 100

        private val COCO_LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog",
            "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
            "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat",
            "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork",
            "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog",
            "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv",
            "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }

    private var interpreter: Interpreter? = null

    @Synchronized
    private fun load(): Interpreter {
        return interpreter ?: Interpreter(
            ModelLoader.loadMappedModel(context, MODEL_PATH),
            Interpreter.Options().apply { setNumThreads(4) }
        ).also { interpreter = it }
    }

    fun detect(uri: Uri): List<ObjectDetectionResult> {
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode image: $uri" }
        }
        return try {
            detect(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun detect(bitmap: Bitmap): List<ObjectDetectionResult> {
        val tflite = load()
        val inputTensor = tflite.getInputTensor(0)
        val inputShape = inputTensor.shape()
        require(inputShape.size == 4 && inputShape[0] == 1) {
            "Unexpected YOLO input shape: ${inputShape.contentToString()}"
        }

        val channelsLast = inputShape[3] == 3
        val inputHeight = if (channelsLast) inputShape[1] else inputShape[2]
        val inputWidth = if (channelsLast) inputShape[2] else inputShape[3]
        require(inputWidth == inputHeight) { "Only square YOLO input is supported" }

        val scale = min(inputWidth.toFloat() / bitmap.width, inputHeight.toFloat() / bitmap.height)
        val resizedWidth = max(1, (bitmap.width * scale).toInt())
        val resizedHeight = max(1, (bitmap.height * scale).toInt())
        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val canvasBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(canvasBitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        val left = (inputWidth - resizedWidth) / 2f
        val top = (inputHeight - resizedHeight) / 2f
        canvas.drawBitmap(resized, left, top, null)
        resized.recycle()

        val input = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputWidth * inputHeight)
        canvasBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        canvasBitmap.recycle()

        val inputType = inputTensor.dataType()
        require(inputType == DataType.FLOAT32) {
            "YOLO w8a32 expects FLOAT32 input, found $inputType"
        }
        if (channelsLast) {
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16) and 0xFF) / 255f)
                input.putFloat(((pixel shr 8) and 0xFF) / 255f)
                input.putFloat((pixel and 0xFF) / 255f)
            }
        } else {
            for (channel in 0..2) {
                for (pixel in pixels) {
                    input.putFloat(
                        when (channel) {
                            0 -> ((pixel shr 16) and 0xFF) / 255f
                            1 -> ((pixel shr 8) and 0xFF) / 255f
                            else -> (pixel and 0xFF) / 255f
                        }
                    )
                }
            }
        }
        input.rewind()

        val outputTensor = tflite.getOutputTensor(0)
        val outputShape = outputTensor.shape()
        require(outputTensor.dataType() == DataType.FLOAT32) {
            "Unsupported YOLO output type: ${outputTensor.dataType()}"
        }
        val outputSize = outputShape.fold(1) { a, b -> a * b }
        val output = Array(1) { FloatArray(outputSize) }
        tflite.run(input, output)

        return parseEndToEnd(output[0], outputShape, inputWidth, inputHeight, scale, left, top)
    }

    private fun parseEndToEnd(
        values: FloatArray,
        shape: IntArray,
        inputWidth: Int,
        inputHeight: Int,
        scale: Float,
        padLeft: Float,
        padTop: Float
    ): List<ObjectDetectionResult> {
        require(shape.size == 3 && shape[2] == 6) {
            "This detector expects YOLO end-to-end output [1,N,6], found ${shape.contentToString()}"
        }

        val count = shape[1]
        val results = ArrayList<ObjectDetectionResult>(min(count, MAX_RESULTS))
        for (i in 0 until count) {
            val offset = i * 6
            val confidence = values[offset + 4]
            if (!confidence.isFinite() || confidence < CONFIDENCE_THRESHOLD) continue

            val classId = values[offset + 5].toInt()
            if (classId !in COCO_LABELS.indices) continue

            val x1 = (values[offset] - padLeft) / scale
            val y1 = (values[offset + 1] - padTop) / scale
            val x2 = (values[offset + 2] - padLeft) / scale
            val y2 = (values[offset + 3] - padTop) / scale
            if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) continue

            results += ObjectDetectionResult(
                classId = classId,
                label = COCO_LABELS[classId],
                confidence = confidence,
                left = x1.coerceAtLeast(0f),
                top = y1.coerceAtLeast(0f),
                right = x2.coerceAtLeast(0f),
                bottom = y2.coerceAtLeast(0f)
            )
            if (results.size >= MAX_RESULTS) break
        }
        return results.sortedByDescending { it.confidence }
    }

    override fun close() {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
        }
    }
}
