package com.example.personalmemoryai.vision

import android.graphics.RectF

data class FaceLandmarkResult(

    val boundingBox: RectF,

    val landmarks: List<Point>,

    val blendshapes: List<Blendshape> = emptyList(),

    val rotationX: Float? = null,

    val rotationY: Float? = null,

    val rotationZ: Float? = null,

    val detectionConfidence: Float = 0f
) {

    data class Point(
        val x: Float,
        val y: Float,
        val z: Float
    )

    data class Blendshape(
        val name: String,
        val score: Float
    )
}
