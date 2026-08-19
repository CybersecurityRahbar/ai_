package com.example.personalmemoryai.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A persisted object observation produced by the on-device object detector. */
@Entity(
    tableName = "object_observations",
    indices = [
        Index(value = ["imageId"]),
        Index(value = ["classId"]),
        Index(value = ["label"]),
        Index(value = ["confidence"])
    ]
)
data class ObjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageId: Long,
    val classId: Int,
    val label: String,
    val arabicLabel: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val detectorName: String,
    val detectorVersion: String,
    val inferenceTimeMs: Long,
    val createdAt: Long
)
