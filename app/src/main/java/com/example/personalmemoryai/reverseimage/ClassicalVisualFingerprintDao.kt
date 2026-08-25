package com.example.personalmemoryai.reverseimage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ClassicalVisualFingerprintDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ClassicalVisualFingerprintEntity): Long

    @Query("SELECT * FROM classical_visual_fingerprints ORDER BY itemId ASC")
    suspend fun getAll(): List<ClassicalVisualFingerprintEntity>

    @Query("SELECT * FROM classical_visual_fingerprints WHERE itemId = :itemId LIMIT 1")
    suspend fun getForItem(itemId: Long): ClassicalVisualFingerprintEntity?

    @Query("SELECT COUNT(*) FROM classical_visual_fingerprints")
    suspend fun count(): Long

    @Query("DELETE FROM classical_visual_fingerprints WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)

    @Query("DELETE FROM classical_visual_fingerprints")
    suspend fun deleteAll()
}
