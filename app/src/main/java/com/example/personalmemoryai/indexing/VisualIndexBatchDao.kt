package com.example.personalmemoryai.indexing

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import com.example.personalmemoryai.advancedvisual.AdvancedVisualFingerprintEntity
import com.example.personalmemoryai.reverseimage.ClassicalVisualFingerprintEntity
import com.example.personalmemoryai.reverseimage.HaarFingerprintEntity

/** Atomic persistence boundary for one bounded visual-index batch. */
@Dao
abstract class VisualIndexBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertHaar(rows: List<HaarFingerprintEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertClassical(rows: List<ClassicalVisualFingerprintEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAdvanced(rows: List<AdvancedVisualFingerprintEntity>)

    @Transaction
    open suspend fun insertBatch(
        haar: List<HaarFingerprintEntity>,
        classical: List<ClassicalVisualFingerprintEntity>,
        advanced: List<AdvancedVisualFingerprintEntity>
    ) {
        if (haar.isNotEmpty()) insertHaar(haar)
        if (classical.isNotEmpty()) insertClassical(classical)
        if (advanced.isNotEmpty()) insertAdvanced(advanced)
    }
}
