package com.example.personalmemoryai.advancedvisual

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AdvancedVisualFingerprintDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AdvancedVisualFingerprintEntity): Long

    @Query("SELECT * FROM advanced_visual_fingerprints WHERE itemId = :itemId LIMIT 1")
    suspend fun getForItem(itemId: Long): AdvancedVisualFingerprintEntity?

    @Query("SELECT * FROM advanced_visual_fingerprints WHERE engineVersion = :engineVersion")
    suspend fun getAll(engineVersion: String): List<AdvancedVisualFingerprintEntity>

    @Query("SELECT COUNT(*) FROM advanced_visual_fingerprints")
    suspend fun count(): Long

    @Query("DELETE FROM advanced_visual_fingerprints")
    suspend fun deleteAll()
}
