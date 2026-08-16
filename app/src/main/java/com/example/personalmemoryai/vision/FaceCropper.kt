package com.example.personalmemoryai.vision

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object FaceCropper {

    /**
     * Crops a face from the source image.
     *
     * An additional margin is added around the detected face
     * so that the embedding model receives some contextual
     * facial information.
     */
    fun crop(
        source: Bitmap,
        normalizedBox: RectF,
        marginRatio: Float = 0.20f
    ): Bitmap? {

        if (
            source.width <= 0 ||
            source.height <= 0
        ) {
            return null
        }

        val left =
            normalizedBox.left *
                source.width

        val top =
            normalizedBox.top *
                source.height

        val right =
            normalizedBox.right *
                source.width

        val bottom =
            normalizedBox.bottom *
                source.height

        val width =
            max(
                1f,
                right - left
            )

        val height =
            max(
                1f,
                bottom - top
            )

        val marginX =
            width * marginRatio

        val marginY =
            height * marginRatio

        val cropLeft =
            floor(
                left - marginX
            )
                .toInt()
                .coerceIn(
                    0,
                    source.width - 1
                )

        val cropTop =
            floor(
                top - marginY
            )
                .toInt()
                .coerceIn(
                    0,
                    source.height - 1
                )

        val cropRight =
            ceil(
                right + marginX
            )
                .toInt()
                .coerceIn(
                    cropLeft + 1,
                    source.width
                )

        val cropBottom =
            ceil(
                bottom + marginY
            )
                .toInt()
                .coerceIn(
                    cropTop + 1,
                    source.height
                )

        val cropWidth =
            min(
                source.width - cropLeft,
                cropRight - cropLeft
            )

        val cropHeight =
            min(
                source.height - cropTop,
                cropBottom - cropTop
            )

        if (
            cropWidth <= 1 ||
            cropHeight <= 1
        ) {
            return null
        }

        return Bitmap.createBitmap(
            source,
            cropLeft,
            cropTop,
            cropWidth,
            cropHeight
        )
    }
}
