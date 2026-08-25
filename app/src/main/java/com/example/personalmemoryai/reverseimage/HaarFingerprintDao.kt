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

    @Query("SELECT * FROM image_fingerprints ORDER BY imageId ASC")
    suspend fun getAll(): List<HaarFingerprintEntity>

    @Query("SELECT * FROM image_fingerprints WHERE imageId = :imageId LIMIT 1")
    suspend fun getForImage(imageId: Long): HaarFingerprintEntity?

    @Query("SELECT COUNT(*) FROM image_fingerprints")
    suspend fun count(): Long

    @Query("DELETE FROM image_fingerprints WHERE imageId = :imageId")
    suspend fun deleteForImage(imageId: Long)

    @Query("DELETE FROM image_fingerprints")
    suspend fun deleteAll()
}
