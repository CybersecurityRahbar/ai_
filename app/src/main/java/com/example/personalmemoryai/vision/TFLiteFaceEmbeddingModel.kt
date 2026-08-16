package com.example.personalmemoryai.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Generic TensorFlow Lite face-embedding runner.
 *
 * Designed for common float32 face-embedding models whose
 * input is an RGB image and whose output is a 1-D feature
 * vector.
 *
 * The class reads the model input/output tensor metadata
 * at runtime instead of hard-coding the embedding dimension.
 */
class TFLiteFaceEmbeddingModel(
    context: Context,
    private val modelFileName: String = DEFAULT_MODEL
) : FaceEmbeddingModel {

    private val interpreter: Interpreter

    private val inputWidth: Int
    private val inputHeight: Int
    private val inputChannels: Int

    private val outputDimension: Int

    override val modelName: String =
        modelFileName.substringBeforeLast(".")

    override val modelVersion: String =
        "tflite-runtime"

    override val embeddingDimension: Int
        get() = outputDimension

    init {

        val modelBuffer =
            ModelLoader.loadMappedModel(
                context = context,
                fileName = modelFileName
            )

        val options =
            Interpreter.Options().apply {

                setNumThreads(
                    DEFAULT_THREADS
                )
            }

        interpreter =
            Interpreter(
                modelBuffer,
                options
            )

        val inputTensor =
            interpreter.getInputTensor(0)

        val inputShape =
            inputTensor.shape()

        require(inputShape.size == 4) {
            "Face embedding model must use a 4D input tensor. " +
                "Actual shape: ${inputShape.contentToString()}"
        }

        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        inputChannels = inputShape[3]

        require(
            inputChannels == 3
        ) {
            "Expected RGB input with 3 channels. " +
                "Actual channels: $inputChannels"
        }

        val outputTensor =
            interpreter.getOutputTensor(0)

        val outputShape =
            outputTensor.shape()

        outputDimension =
            outputShape
                .fold(1) { accumulator, value ->
                    accumulator * value
                }

        require(
            outputDimension > 1
        ) {
            "Invalid face embedding output dimension: " +
                "$outputDimension"
        }
    }

    override suspend fun generateEmbedding(
        faceBitmap: Bitmap
    ): FloatArray {

        require(
            !faceBitmap.isRecycled
        ) {
            "Face bitmap has already been recycled."
        }

        val resized =
            Bitmap.createScaledBitmap(
                faceBitmap,
                inputWidth,
                inputHeight,
                true
            )

        val input =
            bitmapToInputBuffer(
                resized
            )

        val output =
            Array(
                1
            ) {
                FloatArray(
                    outputDimension
                )
            }

        interpreter.run(
            input,
            output
        )

        return FaceSimilarity.normalize(
            output[0]
        )
    }

    private fun bitmapToInputBuffer(
        bitmap: Bitmap
    ): ByteBuffer {

        val bytesPerFloat = 4

        val buffer =
            ByteBuffer.allocateDirect(
                inputWidth *
                    inputHeight *
                    inputChannels *
                    bytesPerFloat
            )

        buffer.order(
            ByteOrder.nativeOrder()
        )

        val pixels =
            IntArray(
                inputWidth *
                    inputHeight
            )

        bitmap.getPixels(
            pixels,
            0,
            inputWidth,
            0,
            0,
            inputWidth,
            inputHeight
        )

        for (pixel in pixels) {

            val r =
                ((pixel shr 16) and 0xFF)

            val g =
                ((pixel shr 8) and 0xFF)

            val b =
                (pixel and 0xFF)

            /*
             * Standard [-1, 1] normalization.
             *
             * IMPORTANT:
             * This is correct only for models trained with this
             * preprocessing convention.
             *
             * If the selected model requires [0, 1] or another
             * preprocessing pipeline, this function must be
             * changed to match that model.
             */
            buffer.putFloat(
                r / 127.5f - 1f
            )

            buffer.putFloat(
                g / 127.5f - 1f
            )

            buffer.putFloat(
                b / 127.5f - 1f
            )
        }

        buffer.rewind()

        return buffer
    }

    override fun close() {
        interpreter.close()
    }

    companion object {

        private const val DEFAULT_MODEL =
            "face_embedding.tflite"

        private const val DEFAULT_THREADS = 4
    }
}
