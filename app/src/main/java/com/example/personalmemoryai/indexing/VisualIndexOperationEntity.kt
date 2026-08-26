package com.example.personalmemoryai.indexing

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable state for a shared visual-index operation. */
@Entity(
    tableName = "visual_index_operations",
    indices = [Index(value = ["status"]), Index(value = ["updatedAt"])]
)
data class VisualIndexOperationEntity(
    @PrimaryKey val id: String,
    val rebuild: Boolean,
    val total: Int,
    val processed: Int,
    val indexed: Int,
    val skipped: Int,
    val failed: Int,
    val localFeatures: Int,
    val status: String,
    val engineHaar: String,
    val engineClassical: String,
    val engineAdvanced: String,
    val startedAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    val lastError: String? = null
)
