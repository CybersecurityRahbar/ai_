package com.example.personalmemoryai.advancedvisual

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "advanced_visual_fingerprints",
    indices = [Index(value = ["itemId"], unique = true), Index(value = ["engineVersion"])]
)
data class AdvancedVisualFingerprintEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val engineVersion: String,
    val grayPyramid: ByteArray,
    val colorMoments: ByteArray,
    val spatialColor: ByteArray,
    val lbpHistogram: ByteArray,
    val spatialLbp: ByteArray,
    val gradientHistogram: ByteArray,
    val gradientMagnitude: ByteArray,
    val layoutSignature: ByteArray,
    val illuminationRobustStructure: ByteArray,
    val entropy: Float,
    val aspectRatio: Float,
    val createdAt: Long = System.currentTimeMillis()
)
