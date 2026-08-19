package com.example.personalmemoryai.indexing

import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.database.FaceDao
import com.example.personalmemoryai.database.PersonDao
import com.example.personalmemoryai.database.PersonEntity
import com.example.personalmemoryai.vision.FaceShapeEncoder
import com.example.personalmemoryai.vision.FaceSimilarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Incremental visual clustering of face observations.
 * A person is a visual cluster, not a verified real-world identity.
 */
class PersonClusteringEngine(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val embeddingDao: EmbeddingDao
) {
    data class ClusterResult(val createdClusters: Int, val assignedFaces: Int)

    private data class Representative(
        val personId: Long,
        var faceId: Long,
        var embedding: FloatArray,
        var shape: FloatArray?
    )

    suspend fun buildClusters(similarityThreshold: Float): ClusterResult = withContext(Dispatchers.Default) {
        require(similarityThreshold in 0.50f..0.95f) { "Cluster threshold must be between 0.50 and 0.95" }

        val candidates = faceDao.getUnassignedMatchableFaces()
        if (candidates.isEmpty()) return@withContext ClusterResult(0, 0)

        val representatives = mutableListOf<Representative>()
        for (person in personDao.getMostObserved()) {
            val faceId = person.representativeFaceId ?: continue
            val identity = embeddingDao.getForOwnerAndModel(
                OWNER_FACE,
                faceId,
                MODEL_FACE,
                MODEL_FACE_VERSION
            ) ?: continue
            if (identity.vector.isEmpty() || !identity.vector.all { it.isFinite() }) continue
            val shape = embeddingDao.getForOwnerAndModel(
                FaceShapeEncoder.OWNER_TYPE,
                faceId,
                FaceShapeEncoder.MODEL_NAME,
                FaceShapeEncoder.MODEL_VERSION
            )?.vector
            representatives += Representative(person.id, faceId, identity.vector, shape)
        }

        var created = 0
        var assigned = 0
        for (face in candidates) {
            val identity = embeddingDao.getForOwnerAndModel(
                OWNER_FACE,
                face.id,
                MODEL_FACE,
                MODEL_FACE_VERSION
            ) ?: continue
            val shape = embeddingDao.getForOwnerAndModel(
                FaceShapeEncoder.OWNER_TYPE,
                face.id,
                FaceShapeEncoder.MODEL_NAME,
                FaceShapeEncoder.MODEL_VERSION
            )?.vector

            var best: Representative? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (candidate in representatives) {
                if (candidate.embedding.size != identity.dimension) continue
                val identityScore = FaceSimilarity.cosineSimilarity(identity.vector, candidate.embedding)
                val shapeScore = if (shape != null && candidate.shape != null) FaceShapeEncoder.similarity(shape, candidate.shape!!) else 0f
                val score = 0.85f * identityScore + 0.15f * shapeScore
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }

            if (best != null && bestScore >= similarityThreshold) {
                faceDao.assignToPerson(face.id, best.personId)
                assigned++
                val representativeQuality = faceDao.getBestQualityForPerson(best.personId) ?: 0f
                if (face.qualityScore > representativeQuality) {
                    best.faceId = face.id
                    best.embedding = identity.vector
                    best.shape = shape
                }
            } else {
                val personId = personDao.insert(
                    PersonEntity(
                        faceCount = 1,
                        bestQualityScore = face.qualityScore,
                        hasRepresentativeEmbedding = true,
                        representativeFaceId = face.id,
                        modelVersion = identity.modelVersion
                    )
                )
                faceDao.assignToPerson(face.id, personId)
                representatives += Representative(personId, face.id, identity.vector, shape)
                created++
                assigned++
            }
        }

        // Refresh persisted cluster statistics after assignments.
        for (person in personDao.getMostObserved()) {
            val count = faceDao.countForPerson(person.id).toInt()
            if (count <= 0) continue
            val bestQuality = faceDao.getBestQualityForPerson(person.id) ?: 0f
            val representativeFaceId = person.representativeFaceId
                ?: faceDao.getByPersonId(person.id).maxByOrNull { it.qualityScore }?.id
            val hasEmbedding = representativeFaceId?.let {
                embeddingDao.getForOwnerAndModel(OWNER_FACE, it, MODEL_FACE, MODEL_FACE_VERSION) != null
            } == true
            personDao.updateStatistics(
                personId = person.id,
                faceCount = count,
                bestQualityScore = bestQuality,
                hasRepresentativeEmbedding = hasEmbedding,
                representativeFaceId = representativeFaceId
            )
        }

        ClusterResult(created, assigned)
    }

    companion object {
        private const val OWNER_FACE = "FACE"
        private const val MODEL_FACE = "mobilefacenet"
        private const val MODEL_FACE_VERSION = "tflite"
    }
}
