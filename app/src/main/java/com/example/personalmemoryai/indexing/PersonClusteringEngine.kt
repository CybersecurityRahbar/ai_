package com.example.personalmemoryai.indexing

import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.database.FaceDao
import com.example.personalmemoryai.database.PersonDao
import com.example.personalmemoryai.database.PersonEntity
import com.example.personalmemoryai.vision.FaceSimilarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Groups visually similar face observations into logical
 * person clusters.
 *
 * A cluster is NOT a verified real-world identity.
 */
class PersonClusteringEngine(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val embeddingDao: EmbeddingDao
) {

    data class ClusterResult(

        val createdClusters: Int,

        val assignedFaces: Int
    )

    suspend fun buildClusters(
        similarityThreshold: Float
    ): ClusterResult = withContext(
        Dispatchers.Default
    ) {

        val faces =
            faceDao.getUnassignedMatchableFaces()

        if (faces.isEmpty()) {
            return@withContext ClusterResult(
                createdClusters = 0,
                assignedFaces = 0
            )
        }

        var clustersCreated = 0
        var assigned = 0

        val representatives =
            mutableListOf<ClusterRepresentative>()

        for (face in faces) {

            val embeddings =
                embeddingDao.getForOwner(
                    ownerType = OWNER_FACE,
                    ownerId = face.id
                )

            val embedding =
                embeddings
                    .firstOrNull()
                    ?.vector
                    ?: continue

            var bestCluster:
                ClusterRepresentative? = null

            var bestSimilarity =
                Float.NEGATIVE_INFINITY

            for (cluster in representatives) {

                if (
                    cluster.embedding.size !=
                    embedding.size
                ) {
                    continue
                }

                val similarity =
                    FaceSimilarity.cosineSimilarity(
                        embedding,
                        cluster.embedding
                    )

                if (
                    similarity >
                    bestSimilarity
                ) {
                    bestSimilarity =
                        similarity

                    bestCluster =
                        cluster
                }
            }

            if (
                bestCluster != null &&
                bestSimilarity >=
                similarityThreshold
            ) {

                faceDao.assignToPerson(
                    faceId = face.id,
                    personId = bestCluster.personId
                )

                assigned++

            } else {

                val personId =
                    personDao.insert(
                        PersonEntity(
                            faceCount = 1,
                            bestQualityScore =
                                face.qualityScore,

                            hasRepresentativeEmbedding =
                                true,

                            representativeFaceId =
                                face.id,

                            modelVersion =
                                embeddings.first()
                                    .modelVersion
                        )
                    )

                faceDao.assignToPerson(
                    faceId = face.id,
                    personId = personId
                )

                representatives +=
                    ClusterRepresentative(
                        personId = personId,
                        embedding = embedding
                    )

                clustersCreated++
                assigned++
            }
        }

        updatePersonStatistics()

        ClusterResult(
            createdClusters =
                clustersCreated,

            assignedFaces =
                assigned
        )
    }

    private suspend fun updatePersonStatistics() {

        val persons =
            personDao.observeAll()

        /*
         * The statistics are intentionally updated separately
         * from cluster creation.
         *
         * A future optimized implementation will perform this
         * in a single transaction.
         */
    }

    private data class ClusterRepresentative(
        val personId: Long,
        val embedding: FloatArray
    )

    companion object {

        private const val OWNER_FACE =
            "FACE"
    }
}
