package com.example.personalmemoryai.reverseimage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HaarFingerprintDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fingerprint: HaarFingerprintEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(fingerprints: List<HaarFingerprintEntity>)

    @Query("SELECT * FROM image_fingerprints ORDER BY itemId ASC")
    suspend fun getAll(): List<HaarFingerprintEntity>

    @Query("SELECT * FROM image_fingerprints WHERE itemId = :itemId LIMIT 1")
    suspend fun getForItem(itemId: Long): HaarFingerprintEntity?

    @Query("SELECT COUNT(*) FROM image_fingerprints")
    suspend fun count(): Long

    @Query("DELETE FROM image_fingerprints WHERE itemId = :itemId")
    suspend fun deleteForItem(itemId: Long)

    @Query("DELETE FROM image_fingerprints")
    suspend fun deleteAll()
}
