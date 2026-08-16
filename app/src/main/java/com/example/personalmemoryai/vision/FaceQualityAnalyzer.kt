package com.example.personalmemoryai.vision

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Performs lightweight image-quality analysis before face
 * embedding generation.
 *
 * This is not an identity classifier.
 *
 * Its purpose is to determine whether the face crop is
 * sufficiently useful for visual matching.
 */
class FaceQualityAnalyzer {

    data class QualityResult(
        val score: Float,
        val sharpness: Float,
        val brightness: Float,
        val contrast: Float,
        val usable: Boolean
    )

    fun analyze(
        bitmap: Bitmap
    ): QualityResult {

        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return QualityResult(
                score = 0f,
                sharpness = 0f,
                brightness = 0f,
                contrast = 0f,
                usable = false
            )
        }

        val sampleWidth = min(bitmap.width, 256)
        val sampleHeight = min(bitmap.height, 256)

        val sampled = Bitmap.createScaledBitmap(
            bitmap,
            sampleWidth,
            sampleHeight,
            true
        )

        val pixels = IntArray(
            sampled.width * sampled.height
        )

        sampled.getPixels(
            pixels,
            0,
            sampled.width,
            0,
            0,
            sampled.width,
            sampled.height
        )

        var brightnessSum = 0.0
        var brightnessSquaredSum = 0.0

        for (pixel in pixels) {

            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val luminance =
                0.299 * r +
                0.587 * g +
                0.114 * b

            brightnessSum += luminance
            brightnessSquaredSum += luminance * luminance
        }

        val count = pixels.size.toDouble()

        val averageBrightness =
            brightnessSum / count

        val variance =
            max(
                0.0,
                brightnessSquaredSum / count -
                    averageBrightness * averageBrightness
            )

        val standardDeviation =
            kotlin.math.sqrt(variance)

        val brightnessScore =
            calculateBrightnessScore(
                averageBrightness
            )

        val contrastScore =
            calculateContrastScore(
                standardDeviation
            )

        val sharpnessScore =
            estimateSharpness(
                pixels,
                sampled.width,
                sampled.height
            )

        val finalScore =
            (
                sharpnessScore * 0.50f +
                brightnessScore * 0.25f +
                contrastScore * 0.25f
            ).coerceIn(0f, 1f)

        return QualityResult(
            score = finalScore,
            sharpness = sharpnessScore,
            brightness = brightnessScore,
            contrast = contrastScore,
            usable = finalScore >= MINIMUM_USABLE_SCORE
        )
    }

    private fun calculateBrightnessScore(
        brightness: Double
    ): Float {

        val distance =
            abs(brightness - IDEAL_BRIGHTNESS)

        return (
            1.0 -
                distance / IDEAL_BRIGHTNESS
        )
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    private fun calculateContrastScore(
        standardDeviation: Double
    ): Float {

        return (
            standardDeviation /
                IDEAL_CONTRAST
        )
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    /**
     * Lightweight gradient-based sharpness estimate.
     *
     * It is intentionally inexpensive because it can run over
     * many images during background indexing.
     */
    private fun estimateSharpness(
        pixels: IntArray,
        width: Int,
        height: Int
    ): Float {

        if (width < 3 || height < 3) {
            return 0f
        }

        var gradientSum = 0.0
        var samples = 0

        for (y in 1 until height - 1) {

            val row = y * width
            val previousRow = (y - 1) * width
            val nextRow = (y + 1) * width

            for (x in 1 until width - 1) {

                val center =
                    luminance(pixels[row + x])

                val left =
                    luminance(pixels[row + x - 1])

                val right =
                    luminance(pixels[row + x + 1])

                val top =
                    luminance(pixels[previousRow + x])

                val bottom =
                    luminance(pixels[nextRow + x])

                val horizontal =
                    abs(right - left)

                val vertical =
                    abs(bottom - top)

                gradientSum +=
                    horizontal + vertical

                samples++
            }
        }

        if (samples == 0) {
            return 0f
        }

        val averageGradient =
            gradientSum / samples

        return (
            averageGradient /
                IDEAL_GRADIENT
        )
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    private fun luminance(
        pixel: Int
    ): Double {

        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF

        return (
            0.299 * r +
            0.587 * g +
            0.114 * b
        )
    }

    companion object {

        private const val IDEAL_BRIGHTNESS = 128.0

        private const val IDEAL_CONTRAST = 64.0

        private const val IDEAL_GRADIENT = 32.0

        private const val MINIMUM_USABLE_SCORE = 0.30f
    }
}
