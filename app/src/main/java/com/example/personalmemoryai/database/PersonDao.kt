package com.example.personalmemoryai.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(persons: List<PersonEntity>)

    @Query("""
        SELECT *
        FROM persons
        WHERE id = :personId
        LIMIT 1
    """)
    suspend fun getById(personId: Long): PersonEntity?

    @Query("""
        SELECT *
        FROM persons
        WHERE id = :personId
        LIMIT 1
    """)
    fun observeById(personId: Long): Flow<PersonEntity?>

    @Query("""
        SELECT *
        FROM persons
        WHERE isArchived = 0
        ORDER BY updatedAt DESC
    """)
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("""
        SELECT *
        FROM persons
        WHERE isArchived = 0
        ORDER BY faceCount DESC, bestQualityScore DESC
    """)
    suspend fun getMostObserved(): List<PersonEntity>

    @Query("""
        SELECT *
        FROM persons
        WHERE displayName LIKE '%' || :query || '%'
        AND isArchived = 0
        ORDER BY updatedAt DESC
    """)
    suspend fun searchByName(query: String): List<PersonEntity>

    @Query("""
        SELECT COUNT(*)
        FROM persons
        WHERE isArchived = 0
    """)
    suspend fun count(): Long

    @Query("""
        UPDATE persons
        SET displayName = :name,
            updatedAt = :updatedAt
        WHERE id = :personId
    """)
    suspend fun rename(
        personId: Long,
        name: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE persons
        SET description = :description,
            updatedAt = :updatedAt
        WHERE id = :personId
    """)
    suspend fun updateDescription(
        personId: Long,
        description: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE persons
        SET faceCount = :faceCount,
            bestQualityScore = :bestQualityScore,
            hasRepresentativeEmbedding = :hasRepresentativeEmbedding,
            representativeFaceId = :representativeFaceId,
            updatedAt = :updatedAt
        WHERE id = :personId
    """)
    suspend fun updateStatistics(
        personId: Long,
        faceCount: Int,
        bestQualityScore: Float,
        hasRepresentativeEmbedding: Boolean,
        representativeFaceId: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE persons
        SET isFavorite = :favorite,
            updatedAt = :updatedAt
        WHERE id = :personId
    """)
    suspend fun setFavorite(
        personId: Long,
        favorite: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("""
        UPDATE persons
        SET isArchived = :archived,
            updatedAt = :updatedAt
        WHERE id = :personId
    """)
    suspend fun setArchived(
        personId: Long,
        archived: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Delete
    suspend fun delete(person: PersonEntity)

    @Query("""
        DELETE FROM persons
        WHERE id = :personId
    """)
    suspend fun deleteById(personId: Long)
}
