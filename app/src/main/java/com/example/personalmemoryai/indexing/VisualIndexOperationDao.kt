package com.example.personalmemoryai.indexing

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface VisualIndexOperationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(operation: VisualIndexOperationEntity)

    @Update
    suspend fun update(operation: VisualIndexOperationEntity)

    @Query("SELECT * FROM visual_index_operations WHERE id = :id LIMIT 1")
    suspend fun find(id: String): VisualIndexOperationEntity?

    @Query("SELECT * FROM visual_index_operations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): VisualIndexOperationEntity?
}
