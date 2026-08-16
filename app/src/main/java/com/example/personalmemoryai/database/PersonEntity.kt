package com.example.personalmemoryai.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Logical person cluster.
 *
 * This entity does not automatically represent a verified
 * real-world identity. It represents a group of visually
 * similar face observations.
 */
@Entity(
    tableName = "persons",
    indices = [
        Index(value = ["displayName"]),
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"])
    ]
)
data class PersonEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * User-assigned name.
     *
     * Example:
     * "Ahmed"
     */
    val displayName: String? = null,

    /**
     * Optional description supplied by the user.
     */
    val description: String? = null,

    /**
     * Number of face observations currently associated
     * with this cluster.
     */
    val faceCount: Int = 0,

    /**
     * Best available face quality in this cluster.
     */
    val bestQualityScore: Float = 0f,

    /**
     * Whether this cluster has a stable representative
     * embedding.
     */
    val hasRepresentativeEmbedding: Boolean = false,

    /**
     * Representative face ID.
     */
    val representativeFaceId: Long? = null,

    /**
     * User-created timestamp.
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Last modification timestamp.
     */
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * Current face-analysis model version.
     */
    val modelVersion: String = "1.0",

    /**
     * Optional user-controlled flag.
     */
    val isFavorite: Boolean = false,

    /**
     * Optional archive state.
     */
    val isArchived: Boolean = false
)
