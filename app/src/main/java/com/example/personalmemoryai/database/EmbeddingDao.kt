package com.example.personalmemoryai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmbeddingDao {

    @Insert(
        onConflict =
            OnConflictStrategy.REPLACE
    )
    suspend fun insert(
        embedding: EmbeddingEntity
    ): Long

    @Query(
        """
        DELETE FROM embeddings
        WHERE ownerType = :ownerType
        AND ownerId = :ownerId
        """
    )
    suspend fun deleteForOwner(
        ownerType: String,
        ownerId: Long
    )

    @Query(
        """
        SELECT * FROM embeddings
        WHERE ownerType = 'FACE'
        """
    )
    suspend fun getAllForFaceSearch():
        List<EmbeddingWithFace>

    @Query(
        """
        SELECT * FROM embeddings
        WHERE ownerType = :ownerType
        AND ownerId = :ownerId
        """
    )
    suspend fun getForOwner(
        ownerType: String,
        ownerId: Long
    ): List<EmbeddingEntity>
}
