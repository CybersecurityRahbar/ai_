package com.example.personalmemoryai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity): Long

    @Query("SELECT * FROM images ORDER BY dateTaken DESC")
    suspend fun getAll(): List<ImageEntity>

    @Query("SELECT * FROM images WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ImageEntity>

    @Query("""
        SELECT * FROM images
        WHERE ocrText LIKE '%' || :query || '%'
        ORDER BY dateTaken DESC
    """)
    suspend fun searchText(query: String): List<ImageEntity>

    @Query("""
        SELECT * FROM images
        WHERE detectedObjects LIKE '%' || :query || '%'
        ORDER BY dateTaken DESC
    """)
    suspend fun searchObjects(query: String): List<ImageEntity>

    @Query("""
        SELECT * FROM images
        WHERE ocrText LIKE '%' || :query || '%'
           OR detectedObjects LIKE '%' || :query || '%'
        ORDER BY dateTaken DESC
    """)
    suspend fun searchTextAndObjects(query: String): List<ImageEntity>

    @Query("UPDATE images SET detectedObjects = :objects WHERE id = :imageId")
    suspend fun updateDetectedObjects(imageId: Long, objects: String)

    @Query("SELECT COUNT(*) FROM images")
    suspend fun count(): Int

    @Query("DELETE FROM images")
    suspend fun deleteAll()

    @Query("SELECT * FROM images WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): ImageEntity?

    /** Original source URI is retained in filePath for portable duplicate detection. */
    @Query("SELECT * FROM images WHERE filePath = :sourceUri LIMIT 1")
    suspend fun findBySourceUri(sourceUri: String): ImageEntity?
}
