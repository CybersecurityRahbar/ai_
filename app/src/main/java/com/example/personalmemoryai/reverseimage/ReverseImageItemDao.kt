package com.example.personalmemoryai.reverseimage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReverseImageItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ReverseImageItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ReverseImageItemEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ReverseImageItemEntity): Long

    @Query("SELECT * FROM reverse_image_items ORDER BY id ASC")
    suspend fun getAll(): List<ReverseImageItemEntity>

    @Query("SELECT * FROM reverse_image_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ReverseImageItemEntity>

    @Query("SELECT * FROM reverse_image_items WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): ReverseImageItemEntity?

    @Query("SELECT * FROM reverse_image_items WHERE uri IN (:uris)")
    suspend fun findByUris(uris: List<String>): List<ReverseImageItemEntity>

    @Query("SELECT COUNT(*) FROM reverse_image_items")
    suspend fun count(): Long

    @Query("DELETE FROM reverse_image_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reverse_image_items")
    suspend fun deleteAll()
}
