package com.example.personalmemoryai.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents one detected face inside an image.
 *
 * A FaceEntity describes the visual occurrence of a face.
 * It does NOT directly claim the person's real-world identity.
 *
 * Identity matching is performed later through embeddings
 * and similarity analysis.
 */
@Entity(
    tableName = "faces",

    foreignKeys = [
        ForeignKey(
            entity = ImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],

    indices = [
        Index(value = ["imageId"]),
        Index(value = ["personId"]),
        Index(value = ["qualityScore"])
    ]
)
data class FaceEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Image containing this face.
     */
    val imageId: Long,

    /**
     * Optional logical person cluster.
     *
     * null means the system has detected the face but
     * has not associated it with a person cluster yet.
     */
    val personId: Long? = null,

    /**
     * Bounding box in normalized coordinates.
     *
     * Values should normally be in the range 0.0..1.0.
     */
    val boundingLeft: Float,

    val boundingTop: Float,

    val boundingRight: Float,

    val boundingBottom: Float,

    /**
     * Face detection confidence.
     */
    val detectionConfidence: Float,

    /**
     * Overall quality of the face crop.
     *
     * Takes into account factors such as:
     * - resolution
     * - blur
     * - visibility
     * - angle
     * - lighting
     */
    val qualityScore: Float,

    /**
     * Estimated head rotation.
     */
    val rotationX: Float? = null,

    val rotationY: Float? = null,

    val rotationZ: Float? = null,

    /**
     * Whether a face embedding has already been generated.
     */
    val hasEmbedding: Boolean = false,

    /**
     * Whether facial landmarks are available.
     */
    val hasLandmarks: Boolean = false,

    /**
     * Number of visible facial landmarks/points when available.
     */
    val landmarkCount: Int = 0,

    /**
     * Whether the face is partially occluded.
     */
    val isOccluded: Boolean = false,

    /**
     * Whether the face is considered usable for identity
     * similarity search.
     */
    val usableForMatching: Boolean = false,

    /**
     * Timestamp when this face was analyzed.
     */
    val analyzedAt: Long = System.currentTimeMillis(),

    /**
     * Analyzer/model version that produced this record.
     */
    val analyzerVersion: String = "1.0"
)
