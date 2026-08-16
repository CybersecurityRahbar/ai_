package com.example.personalmemoryai.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(face: FaceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(faces: List<FaceEntity>)

    @Query("SELECT * FROM faces WHERE id = :faceId LIMIT 1")
    suspend fun getById(faceId: Long): FaceEntity?

    @Query("""
        SELECT *
        FROM faces
        WHERE imageId = :imageId
        ORDER BY qualityScore DESC
    """)
    suspend fun getByImageId(imageId: Long): List<FaceEntity>

    @Query("""
        SELECT *
        FROM faces
        WHERE imageId = :imageId
        ORDER BY qualityScore DESC
    """)
    fun observeByImageId(imageId: Long): Flow<List<FaceEntity>>

    @Query("""
        SELECT *
        FROM faces
        WHERE personId = :personId
        ORDER BY qualityScore DESC
    """)
    suspend fun getByPersonId(personId: Long): List<FaceEntity>

    @Query("""
        SELECT *
        FROM faces
        WHERE usableForMatching = 1
        AND hasEmbedding = 1
        ORDER BY qualityScore DESC
    """)
    suspend fun getMatchableFaces(): List<FaceEntity>

    @Query("""
        SELECT COUNT(*)
        FROM faces
    """)
    suspend fun count(): Long

    @Query("""
        SELECT COUNT(*)
        FROM faces
        WHERE hasEmbedding = 1
    """)
    suspend fun countWithEmbeddings(): Long

    @Query("""
        SELECT COUNT(*)
        FROM faces
        WHERE usableForMatching = 1
    """)
    suspend fun countMatchable(): Long

    @Query("""
        UPDATE faces
        SET personId = :personId
        WHERE id = :faceId
    """)
    suspend fun assignToPerson(
        faceId: Long,
        personId: Long
    )

    @Query("""
        UPDATE faces
        SET hasEmbedding = :hasEmbedding,
            usableForMatching = :usableForMatching
        WHERE id = :faceId
    """)
    suspend fun updateEmbeddingStatus(
        faceId: Long,
        hasEmbedding: Boolean,
        usableForMatching: Boolean
    )

    @Delete
    suspend fun delete(face: FaceEntity)

    @Query("DELETE FROM faces WHERE imageId = :imageId")
    suspend fun deleteByImageId(imageId: Long)

    @Query("DELETE FROM faces")
    suspend fun deleteAll()
}
