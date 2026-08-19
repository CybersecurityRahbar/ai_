package com.example.personalmemoryai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: EmbeddingEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(embeddings: List<EmbeddingEntity>)

    @Query("DELETE FROM embeddings WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteForOwner(ownerType: String, ownerId: Long)

    @Query("SELECT * FROM embeddings WHERE ownerType = 'FACE' ORDER BY id ASC")
    suspend fun getAllForFaceSearch(): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE ownerType = 'IMAGE' ORDER BY id ASC")
    suspend fun getAllForImageSearch(): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE ownerType = :ownerType ORDER BY id ASC")
    suspend fun getAllForOwnerType(ownerType: String): List<EmbeddingEntity>

    @Query("SELECT * FROM embeddings WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY id ASC")
    suspend fun getForOwner(ownerType: String, ownerId: Long): List<EmbeddingEntity>

    @Query("""
        SELECT * FROM embeddings
        WHERE ownerType = :ownerType
          AND ownerId = :ownerId
          AND modelName = :modelName
          AND modelVersion = :modelVersion
        ORDER BY id DESC
        LIMIT 1
    """)
    suspend fun getForOwnerAndModel(
        ownerType: String,
        ownerId: Long,
        modelName: String,
        modelVersion: String
    ): EmbeddingEntity?

    @Query("SELECT COUNT(*) FROM embeddings WHERE ownerType = :ownerType")
    suspend fun countByOwnerType(ownerType: String): Long

    @Query("SELECT COUNT(*) FROM embeddings")
    suspend fun count(): Long

    @Query("DELETE FROM embeddings")
    suspend fun deleteAll()
}
