package com.example.personalmemoryai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ObjectDao {
    @Insert
    suspend fun insertAll(objects: List<ObjectEntity>): List<Long>

    @Query("DELETE FROM object_observations WHERE imageId = :imageId")
    suspend fun deleteForImage(imageId: Long)

    @Query("SELECT * FROM object_observations WHERE imageId = :imageId ORDER BY confidence DESC")
    suspend fun findForImage(imageId: Long): List<ObjectEntity>

    @Query("""
        SELECT o.*, i.fileName AS imageFileName, i.uri AS imageUri
        FROM object_observations o
        INNER JOIN images i ON i.id = o.imageId
        WHERE o.label = :label
        ORDER BY o.confidence DESC
        LIMIT :limit
    """)
    suspend fun searchEvidence(label: String, limit: Int = 500): List<ObjectEvidenceRow>

    @Query("SELECT * FROM object_observations WHERE label = :label ORDER BY confidence DESC LIMIT :limit")
    suspend fun searchByLabel(label: String, limit: Int = 500): List<ObjectEntity>

    @Query("SELECT COUNT(*) FROM object_observations")
    suspend fun count(): Int

    @Query("SELECT COUNT(DISTINCT imageId) FROM object_observations")
    suspend fun indexedImageCount(): Int

    @Query("SELECT label, COUNT(*) AS total FROM object_observations GROUP BY label ORDER BY total DESC LIMIT :limit")
    suspend fun topLabels(limit: Int = 50): List<ObjectLabelCount>
}

data class ObjectLabelCount(
    val label: String,
    val total: Int
)

data class ObjectEvidenceRow(
    val id: Long,
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
    val createdAt: Long,
    val imageFileName: String,
    val imageUri: String
)
