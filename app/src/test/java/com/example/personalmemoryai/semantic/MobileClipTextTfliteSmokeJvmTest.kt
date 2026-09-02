package com.example.personalmemoryai.semantic

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Executes the real pinned MobileCLIP-S2 Text TFLite model in a JVM test.
 *
 * The model binary is downloaded by CI from the pinned Hugging Face revision,
 * SHA-256 verified, and supplied through the mobileclip.text.model system property.
 */
class MobileClipTextTfliteSmokeJvmTest {

    @Test
    fun tokenizedQueriesProduceFiniteNonZero512DEmbeddings() {
        val modelPath = System.getProperty("mobileclip.text.model")
            ?: error("Missing -Dmobileclip.text.model=<path>")
        val modelFile = File(modelPath)
        assertTrue("Text TFLite file missing: $modelPath", modelFile.isFile)
        assertTrue("Text TFLite file is unexpectedly small", modelFile.length() >= 100L * 1024L * 1024L)

        val assets = File("src/main/assets/models/semantic/openclip")
        val vocab = File(assets, "vocab.json")
        val merges = File(assets, "merges.txt")
        assertTrue(vocab.isFile && vocab.length() > 0)
        assertTrue(merges.isFile && merges.length() > 0)
        val tokenizer = OpenClipTokenizer(
            vocab.readText(Charsets.UTF_8),
            merges.readText(Charsets.UTF_8)
        )

        FileInputStream(modelFile).channel.use { channel ->
            val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            Interpreter(mapped).use { interpreter ->
                assertEquals(1, interpreter.inputTensorCount)
                assertEquals(1, interpreter.outputTensorCount)

                val inputTensor = interpreter.getInputTensor(0)
                assertEquals(DataType.INT64, inputTensor.dataType())
                assertTrue(inputTensor.shape().contentEquals(intArrayOf(1, OpenClipTokenizer.CONTEXT_LENGTH)))

                val outputTensor = interpreter.getOutputTensor(0)
                assertEquals(DataType.FLOAT32, outputTensor.dataType())
                assertEquals(512, outputTensor.numElements())

                val queries = listOf(
                    "a diagram",
                    "a photo of a person",
                    "two people in a room",
                    "Arabic العربية",
                    "1234567890",
                    "symbols !@#$%^&*()"
                )

                queries.forEach { text ->
                    val ids = tokenizer.encode(text)
                    assertEquals(OpenClipTokenizer.CONTEXT_LENGTH, ids.size)

                    val first = infer(interpreter, ids)
                    assertFiniteAndNonZero(first.raw)
                    val normalized = normalize(first.raw.copyOf())
                    assertFiniteAndNonZero(normalized)
                    val norm = l2Norm(normalized)
                    assertTrue("Normalized norm drift for '$text': $norm", abs(norm - 1.0) < 1e-5)

                    val second = infer(interpreter, ids)
                    assertEquals("Non-deterministic output for '$text'", 0f, maxAbsDifference(first.raw, second.raw), 0f)
                }
            }
        }
    }

    private data class InferenceResult(val raw: FloatArray)

    private fun infer(interpreter: Interpreter, ids: LongArray): InferenceResult {
        val input = ByteBuffer
            .allocateDirect(ids.size * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        ids.forEach(input::putLong)
        input.rewind()

        val output = ByteBuffer
            .allocateDirect(512 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        return InferenceResult(FloatArray(512) { output.float })
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val norm = l2Norm(vector)
        require(norm.isFinite() && norm > 0.0) { "Embedding norm is invalid: $norm" }
        for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
        return vector
    }

    private fun l2Norm(vector: FloatArray): Double {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value.toDouble()
        return sqrt(sum)
    }

    private fun assertFiniteAndNonZero(vector: FloatArray) {
        assertTrue("Embedding contains NaN/Infinity", vector.all { it.isFinite() })
        assertTrue("Embedding is all-zero", vector.any { it != 0f })
    }

    private fun maxAbsDifference(left: FloatArray, right: FloatArray): Float {
        var max = 0f
        for (i in left.indices) max = maxOf(max, abs(left[i] - right[i]))
        return max
    }
}
