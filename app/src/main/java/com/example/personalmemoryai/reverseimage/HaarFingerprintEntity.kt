package com.example.personalmemoryai.reverseimage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persistent local fingerprint used only by the standalone reverse-image engine. */
@Entity(
    tableName = "image_fingerprints",
    indices = [
        Index(value = ["imageId"], unique = true),
        Index(value = ["engineVersion"]),
        Index(value = ["sourceModifiedAt"])
    ]
)
data class HaarFingerprintEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imageId: Long,
    val engineVersion: String,
    val sourceModifiedAt: Long?,
    val width: Int,
    val height: Int,
    val channels: Int,
    val signature: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
)
