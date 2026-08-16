package com.example.personalmemoryai.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embedding: EmbeddingEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(
        embeddings: List<EmbeddingEntity>
    )

    @Query("""
        SELECT *
        FROM embeddings
        WHERE id = :embeddingId
        LIMIT 1
    """)
    suspend fun getById(
        embeddingId: Long
    ): EmbeddingEntity?

    @Query("""
        SELECT *
        FROM embeddings
        WHERE ownerType = :ownerType
        AND ownerId = :ownerId
        ORDER BY createdAt DESC
    """)
    suspend fun getForOwner(
        ownerType: String,
        ownerId: Long
    ): List<EmbeddingEntity>

    @Query("""
        SELECT *
        FROM embeddings
        WHERE ownerType = 'FACE'
        AND modelName = :modelName
        AND modelVersion = :modelVersion
        AND dimension = :dimension
    """)
    suspend fun getFaceEmbeddings(
        modelName: String,
        modelVersion: String,
        dimension: Int
    ): List<EmbeddingEntity>

    @Query("""
        SELECT COUNT(*)
        FROM embeddings
    """)
    suspend fun count(): Long

    @Query("""
        SELECT COUNT(*)
        FROM embeddings
        WHERE ownerType = 'FACE'
    """)
    suspend fun countFaceEmbeddings(): Long

    @Query("""
        DELETE FROM embeddings
        WHERE ownerType = :ownerType
        AND ownerId = :ownerId
    """)
    suspend fun deleteForOwner(
        ownerType: String,
        ownerId: Long
    )

    @Delete
    suspend fun delete(
        embedding: EmbeddingEntity
    )
}
