package com.example.personalmemoryai.reverseimage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "classical_visual_fingerprints",
    indices = [
        Index(value = ["itemId"], unique = true),
        Index(value = ["engineVersion"])
    ]
)
data class ClassicalVisualFingerprintEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val engineVersion: String,
    val phash: Long,
    val dhash: Long,
    val colorHistogram: ByteArray,
    val edgeHistogram: ByteArray,
    val localKeypoints: ByteArray?,
    val localDescriptors: ByteArray?,
    val localDescriptorRows: Int,
    val localDescriptorCols: Int,
    val localDescriptorType: Int,
    val createdAt: Long = System.currentTimeMillis()
)
