package com.example.personalmemoryai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmbeddingDao {

    @Insert(
        onConflict = OnConflictStrategy.REPLACE
    )
    suspend fun insert(
        embedding: EmbeddingEntity
    ): Long

    @Insert(
        onConflict = OnConflictStrategy.REPLACE
    )
    suspend fun insertAll(
        embeddings: List<EmbeddingEntity>
    )

    /**
     * Delete all embeddings belonging to one owner.
     *
     * Examples:
     * ownerType = FACE
     * ownerType = IMAGE
     * ownerType = OBJECT
     */
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

    /**
     * Returns all face embeddings.
     *
     * The actual FaceEntity records are retrieved separately
     * through FaceDao. This keeps the embedding table generic
     * and allows it to support FACE, IMAGE and OBJECT vectors.
     */
    @Query(
        """
        SELECT *
        FROM embeddings
        WHERE ownerType = 'FACE'
        ORDER BY id ASC
        """
    )
    suspend fun getAllForFaceSearch():
        List<EmbeddingEntity>

    /**
     * Returns all embeddings belonging to a specific owner.
     */
    @Query(
        """
        SELECT *
        FROM embeddings
        WHERE ownerType = :ownerType
        AND ownerId = :ownerId
        ORDER BY id ASC
        """
    )
    suspend fun getForOwner(
        ownerType: String,
        ownerId: Long
    ): List<EmbeddingEntity>

    /**
     * Returns one embedding for an owner and model.
     *
     * This is useful when multiple embedding models exist
     * in the future.
     */
    @Query(
        """
        SELECT *
        FROM embeddings
        WHERE ownerType = :ownerType
        AND ownerId = :ownerId
        AND modelName = :modelName
        AND modelVersion = :modelVersion
        ORDER BY id DESC
        LIMIT 1
        """
    )
    suspend fun getForOwnerAndModel(
        ownerType: String,
        ownerId: Long,
        modelName: String,
        modelVersion: String
    ): EmbeddingEntity?

    /**
     * Returns the number of embeddings belonging to an owner type.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM embeddings
        WHERE ownerType = :ownerType
        """
    )
    suspend fun countByOwnerType(
        ownerType: String
    ): Long

    /**
     * Returns the total number of stored embeddings.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM embeddings
        """
    )
    suspend fun count(): Long

    /**
     * Deletes every stored embedding.
     */
    @Query(
        """
        DELETE FROM embeddings
        """
    )
    suspend fun deleteAll()
}
