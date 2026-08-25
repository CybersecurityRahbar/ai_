package com.example.personalmemoryai.reverseimage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Image corpus owned exclusively by the standalone reverse-image feature. */
@Entity(
    tableName = "reverse_image_items",
    indices = [Index(value = ["uri"], unique = true)]
)
data class ReverseImageItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val filePath: String?,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val sourceModifiedAt: Long?,
    val addedAt: Long = System.currentTimeMillis()
)
