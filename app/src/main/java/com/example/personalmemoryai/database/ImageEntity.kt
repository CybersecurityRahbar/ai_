package com.example.personalmemoryai.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val fileName: String,
    val filePath: String?,
    val dateTaken: Long?,
    val dateModified: Long?,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val ocrText: String,
    val ocrLanguage: String,
    val ocrQualityScore: Float = 0f,
    val ocrPassCount: Int = 0,
    val ocrSuccessfulPasses: Int = 0,
    val ocrLatinCharacters: Int = 0,
    val ocrArabicCharacters: Int = 0,
    /** JSON-like compact list of detected object labels and confidences. */
    val detectedObjects: String,
    val indexedAt: Long
)
