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

    @Query("""
        UPDATE images SET
            ocrText = :ocrText,
            ocrLanguage = :ocrLanguage,
            ocrQualityScore = :ocrQualityScore,
            ocrPassCount = :ocrPassCount,
            ocrSuccessfulPasses = :ocrSuccessfulPasses,
            ocrLatinCharacters = :ocrLatinCharacters,
            ocrArabicCharacters = :ocrArabicCharacters,
            detectedObjects = :detectedObjects,
            indexedAt = :indexedAt
        WHERE id = :imageId
    """)
    suspend fun updateAnalysis(
        imageId: Long,
        ocrText: String,
        ocrLanguage: String,
        ocrQualityScore: Float,
        ocrPassCount: Int,
        ocrSuccessfulPasses: Int,
        ocrLatinCharacters: Int,
        ocrArabicCharacters: Int,
        detectedObjects: String,
        indexedAt: Long
    )

    @Query("UPDATE images SET detectedObjects = :objects WHERE id = :imageId")
    suspend fun updateDetectedObjects(imageId: Long, objects: String)

    @Query("SELECT COUNT(*) FROM images")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM images WHERE LENGTH(TRIM(ocrText)) > 0")
    suspend fun countWithOcr(): Long

    @Query("SELECT COUNT(*) FROM images WHERE LENGTH(TRIM(detectedObjects)) > 0 AND detectedObjects != '[]' AND detectedObjects != '{}'")
    suspend fun countWithDetectedObjects(): Long

    @Query("SELECT AVG(ocrQualityScore) FROM images WHERE ocrPassCount > 0")
    suspend fun averageOcrQuality(): Float?

    @Query("SELECT COUNT(*) FROM images WHERE ocrPassCount > 0")
    suspend fun countOcrAttempted(): Long

    @Query("DELETE FROM images")
    suspend fun deleteAll()

    @Query("SELECT * FROM images WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): ImageEntity?

    /** Original source URI is retained in filePath for portable duplicate detection. */
    @Query("SELECT * FROM images WHERE filePath = :sourceUri LIMIT 1")
    suspend fun findBySourceUri(sourceUri: String): ImageEntity?
}
