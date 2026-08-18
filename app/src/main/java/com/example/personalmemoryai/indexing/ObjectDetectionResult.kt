package com.example.personalmemoryai.indexing

data class ObjectDetectionResult(
    val classId: Int,
    val label: String,
    val arabicLabel: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
