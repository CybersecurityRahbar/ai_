package com.example.personalmemoryai.indexing

import android.graphics.Bitmap
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.database.FaceDao
import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.vision.FaceAnalysisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Indexes faces from images and stores the resulting
 * metadata and embeddings locally.
 *
 * This service is deliberately independent from the UI.
 */
class FaceIndexingService(
    private val faceDao: FaceDao,
    private val embeddingDao: EmbeddingDao,
    private val analysisService: FaceAnalysisService
) {

    data class IndexResult(

        val imageId: Long,

        val detectedFaces: Int,

        val indexedFaces: Int,

        val rejectedFaces: Int
    )

    /**
     * Analyze and index all faces found in one image.
     */
    suspend fun indexImage(
        imageId: Long,
        bitmap: Bitmap
    ): IndexResult = withContext(
        Dispatchers.Default
    ) {

        val analyzedFaces =
            analysisService.analyze(bitmap)

        var indexed = 0
        var rejected = 0

        for (face in analyzedFaces) {

            val box =
                face.detection.boundingBox

            val faceEntity =
                FaceEntity(
                    imageId = imageId,

                    boundingLeft =
                        box.left,

                    boundingTop =
                        box.top,

                    boundingRight =
                        box.right,

                    boundingBottom =
                        box.bottom,

                    detectionConfidence =
                        face.detection.detectionConfidence,

                    qualityScore =
                        face.quality.score,

                    hasEmbedding =
                        face.embedding != null,

                    hasLandmarks = true,

                    landmarkCount =
                        face.detection.landmarks.size,

                    isOccluded =
                        detectOcclusion(face),

                    usableForMatching =
                        face.usableForMatching,

                    analyzedAt =
                        System.currentTimeMillis(),

                    analyzerVersion =
                        buildAnalyzerVersion(
                            face
                        )
                )

            val faceId =
                faceDao.insert(
                    faceEntity
                )

            val embedding =
                face.embedding

            if (
                embedding != null &&
                embedding.isNotEmpty()
            ) {

                val embeddingEntity =
                    EmbeddingEntity(
                        ownerType = OWNER_FACE,

                        ownerId = faceId,

                        vector = embedding,

                        dimension =
                            embedding.size,

                        modelName =
                            face.embeddingModelName
                                ?: "unknown",

                        modelVersion =
                            face.embeddingModelVersion
                                ?: "unknown",

                        normalized = true,

                        createdAt =
                            System.currentTimeMillis()
                    )

                embeddingDao.insert(
                    embeddingEntity
                )

                indexed++

            } else {

                rejected++
            }
        }

        IndexResult(
            imageId = imageId,

            detectedFaces =
                analyzedFaces.size,

            indexedFaces =
                indexed,

            rejectedFaces =
                rejected
        )
    }

    /**
     * Removes all face data associated with one image.
     */
    suspend fun removeImageIndex(
        imageId: Long
    ) {

        val faces =
            faceDao.getByImageId(
                imageId
            )

        for (face in faces) {

            embeddingDao.deleteForOwner(
                ownerType = OWNER_FACE,
                ownerId = face.id
            )
        }

        faceDao.deleteByImageId(
            imageId
        )
    }

    private fun detectOcclusion(
        face: FaceAnalysisService.AnalyzedFace
    ): Boolean {

        /*
         * We intentionally do not infer sensitive attributes.
         *
         * Occlusion here refers only to whether facial
         * geometry/landmark confidence appears insufficient.
         */
        return face.quality.score < 0.35f
    }

    private fun buildAnalyzerVersion(
        face: FaceAnalysisService.AnalyzedFace
    ): String {

        val model =
            face.embeddingModelName
                ?: "none"

        val version =
            face.embeddingModelVersion
                ?: "none"

        return "vision-1.0/$model/$version"
    }

  

    companion object {

        private const val OWNER_FACE =
            "FACE"
    }
}
