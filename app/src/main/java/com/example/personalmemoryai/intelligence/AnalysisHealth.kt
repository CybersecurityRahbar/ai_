package com.example.personalmemoryai.intelligence

/** Structured health snapshot used by UI/diagnostics without hiding partial results. */
data class AnalysisHealth(
    val imagesSeen: Int = 0,
    val imagesSucceeded: Int = 0,
    val imagesFailed: Int = 0,
    val facesDetected: Int = 0,
    val facesWithEmbeddings: Int = 0,
    val facesMatchable: Int = 0,
    val visualEmbeddings: Int = 0,
    val objectLabels: Int = 0,
    val ocrCharacters: Long = 0,
    val warnings: Int = 0,
    val errors: Int = 0
) {
    val imageSuccessRate: Float
        get() = if (imagesSeen == 0) 0f else imagesSucceeded.toFloat() / imagesSeen

    val faceEmbeddingRate: Float
        get() = if (facesDetected == 0) 0f else facesWithEmbeddings.toFloat() / facesDetected

    val visualIndexReady: Boolean
        get() = visualEmbeddings > 0

    val faceIndexReady: Boolean
        get() = facesMatchable > 0

    /** Human-readable state that distinguishes EMPTY from FAILED. */
    fun state(): State = when {
        errors > 0 && imagesSucceeded == 0 && imagesSeen > 0 -> State.FAILED
        imagesSeen == 0 -> State.EMPTY
        warnings > 0 || imagesFailed > 0 -> State.PARTIAL
        else -> State.HEALTHY
    }

    enum class State { EMPTY, HEALTHY, PARTIAL, FAILED }
}
