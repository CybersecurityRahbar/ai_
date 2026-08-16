package com.example.personalmemoryai.repository

import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.database.FaceDao
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.vision.FaceMatchingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaceRepository(
    private val faceDao: FaceDao,
    private val embeddingDao: EmbeddingDao
) {

    suspend fun saveFace(
        face: FaceEntity,
        embedding: FloatArray?,
        modelName: String?,
        modelVersion: String?
    ): Long = withContext(
        Dispatchers.IO
    ) {

        val faceId =
            faceDao.insert(
                face
            )

        if (
            embedding != null &&
            embedding.isNotEmpty()
        ) {

            embeddingDao.insert(
                EmbeddingEntity(
                    ownerType = OWNER_FACE,
                    ownerId = faceId,
                    vector = embedding,
                    dimension = embedding.size,
                    modelName =
                        modelName ?: "unknown",
                    modelVersion =
                        modelVersion ?: "unknown",
                    normalized = true,
                    createdAt =
                        System.currentTimeMillis()
                )
            )
        }

        faceId
    }

    suspend fun deleteFace(
        faceId: Long
    ) = withContext(
        Dispatchers.IO
    ) {

        embeddingDao.deleteForOwner(
            OWNER_FACE,
            faceId
        )

        faceDao.deleteById(
            faceId
        )
    }

    suspend fun getCandidates(
        embeddings: List<EmbeddingEntity>
    ): List<FaceMatchingEngine.Candidate> {

        return withContext(
            Dispatchers.Default
        ) {

            embeddings.mapNotNull { embedding ->

                if (
                    embedding.vector.isEmpty()
                ) {
                    return@mapNotNull null
                }

                val face =
                    faceDao.getById(
                        embedding.ownerId
                    )
                        ?: return@mapNotNull null

                FaceMatchingEngine.Candidate(

                    faceId =
                        face.id,

                    personId =
                        face.personId,

                    embedding =
                        embedding.vector,

                    qualityScore =
                        face.qualityScore
                )
            }
        }
    }

    companion object {

        private const val OWNER_FACE =
            "FACE"
    }
}
