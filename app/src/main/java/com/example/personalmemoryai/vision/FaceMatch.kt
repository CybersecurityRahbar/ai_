package com.example.personalmemoryai.vision

data class FaceMatch(

    val faceId: Long,

    val personId: Long?,

    val similarity: Float,

    val distance: Float,

    val qualityScore: Float,

    val rank: Int
)
