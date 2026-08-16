package com.example.personalmemoryai.repository

import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceDao
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
    ): Long = withContext(Dispatchers.IO) {

        val validEmbedding =
            embedding
                ?.takeIf { it.isNotEmpty() }

        val faceToStore =
            if (validEmbedding != null) {
                face.copy(
                    hasEmbedding = true,
                    usableForMatching = face.usableForMatching
                )
            } else {
                face.copy(
                    hasEmbedding = false,
                    usableForMatching = false
                )
            }

        val faceId =
            faceDao.insert(faceToStore)

        if (validEmbedding != null) {

            embeddingDao.insert(
                EmbeddingEntity(
                    ownerType = OWNER_FACE,
                    ownerId = faceId,
                    vector = validEmbedding,
                    dimension = validEmbedding.size,
                    modelName = modelName ?: MODEL_UNKNOWN,
                    modelVersion = modelVersion ?: MODEL_UNKNOWN,
                    normalized = true,
                    createdAt = System.currentTimeMillis()
                )
            )

            faceDao.updateEmbeddingStatus(
                faceId = faceId,
                hasEmbedding = true,
                usableForMatching = face.usableForMatching
            )
        }

        faceId
    }

    suspend fun deleteFace(
        faceId: Long
    ) = withContext(Dispatchers.IO) {

        /*
         * Delete the vector first because EmbeddingEntity
         * currently uses a generic owner reference rather
         * than a Room foreign key.
         */
        embeddingDao.deleteForOwner(
            ownerType = OWNER_FACE,
            ownerId = faceId
        )

        faceDao.deleteById(
            faceId
        )
    }

    suspend fun getCandidates(
        embeddings: List<EmbeddingEntity>
    ): List<FaceMatchingEngine.Candidate> {

        return withContext(Dispatchers.Default) {

            embeddings.mapNotNull { embedding ->

                if (
                    embedding.ownerType != OWNER_FACE ||
                    embedding.vector.isEmpty()
                ) {
                    return@mapNotNull null
                }

                val face =
                    faceDao.getById(
                        embedding.ownerId
                    )
                        ?: return@mapNotNull null

                if (!face.hasEmbedding) {
                    return@mapNotNull null
                }

                FaceMatchingEngine.Candidate(
                    faceId = face.id,
                    personId = face.personId,
                    embedding = embedding.vector,
                    qualityScore = face.qualityScore
                )
            }
        }
    }

    companion object {

        private const val OWNER_FACE = "FACE"

        private const val MODEL_UNKNOWN = "unknown"
    }
}
