package com.example.personalmemoryai.indexing

import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.database.FaceDao
import com.example.personalmemoryai.database.PersonDao
import com.example.personalmemoryai.database.PersonEntity
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.vision.FaceShapeEncoder
import com.example.personalmemoryai.vision.IdentityEvidenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Multi-model person clustering with conservative visual evidence. */
class PersonClusteringEngine(
    private val faceDao: FaceDao,
    private val personDao: PersonDao,
    private val embeddingDao: EmbeddingDao
) {
    data class ClusterResult(val createdClusters: Int, val assignedFaces: Int)

    suspend fun buildClusters(similarityThreshold: Float): ClusterResult = withContext(Dispatchers.Default) {
        require(similarityThreshold in 0.50f..0.95f) { "Cluster threshold must be between 0.50 and 0.95" }
        val candidates = faceDao.getUnassignedMatchableFaces()
        if (candidates.isEmpty()) return@withContext ClusterResult(0, 0)

        val allIdentity = embeddingDao.getAllForFaceSearch()
        val mobileByFace = allIdentity.filter { it.modelName.equals("mobilefacenet", true) }.groupBy { it.ownerId }.mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }
        val faceNetByFace = allIdentity.filter { it.modelName.equals("facenet_512", true) }.groupBy { it.ownerId }.mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }
        val shapeByFace = embeddingDao.getAllForOwnerType(FaceShapeEncoder.OWNER_TYPE).groupBy { it.ownerId }.mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }

        fun template(face: FaceEntity): IdentityEvidenceEngine.Template? {
            if (!face.usableForMatching) return null
            val identity = mobileByFace[face.id]; val secondary = faceNetByFace[face.id]; val shape = shapeByFace[face.id]
            if (identity == null && secondary == null && shape == null) return null
            return IdentityEvidenceEngine.Template(face, identity, shape, secondary)
        }

        val people = personDao.getMostObserved().mapNotNull { person ->
            val templates = faceDao.getByPersonId(person.id).mapNotNull { template(it) }
            if (templates.isEmpty()) null else ClusterCandidate(person, templates)
        }.toMutableList()

        var created = 0; var assigned = 0
        for (face in candidates.sortedByDescending { it.qualityScore }) {
            val identity = mobileByFace[face.id]; val secondary = faceNetByFace[face.id]; val shape = shapeByFace[face.id]
            if (identity == null && secondary == null && shape == null) continue
            val best = people.mapNotNull { candidate -> IdentityEvidenceEngine.compare(face, identity, shape, candidate.templates, secondary)?.let { candidate to it } }.maxByOrNull { it.second.composite }
            if (best != null && IdentityEvidenceEngine.shouldAssociate(best.second)) {
                faceDao.assignToPerson(face.id, best.first.person.id); assigned++
                val refreshed = faceDao.getByPersonId(best.first.person.id).mapNotNull { template(it) }
                val index = people.indexOf(best.first); if (index >= 0) people[index] = best.first.copy(templates = boundedTemplates(refreshed))
            } else {
                val personId = personDao.insert(PersonEntity(faceCount = 1, bestQualityScore = face.qualityScore.coerceIn(0f, 1f), hasRepresentativeEmbedding = identity != null || secondary != null, representativeFaceId = face.id, modelVersion = "identity-evidence-v5"))
                faceDao.assignToPerson(face.id, personId); people += ClusterCandidate(personDao.getById(personId)!!, listOfNotNull(template(face))); created++; assigned++
            }
        }

        for (person in personDao.getMostObserved()) {
            val faces = faceDao.getByPersonId(person.id); if (faces.isEmpty()) continue
            val bestFace = faces.maxByOrNull { it.qualityScore }
            personDao.updateStatistics(person.id, faces.size, faces.maxOf { it.qualityScore }.coerceIn(0f, 1f), bestFace?.let { mobileByFace[it.id] != null || faceNetByFace[it.id] != null } == true, bestFace?.id)
        }
        ClusterResult(created, assigned)
    }

    private data class ClusterCandidate(val person: PersonEntity, val templates: List<IdentityEvidenceEngine.Template>)
    private fun boundedTemplates(input: List<IdentityEvidenceEngine.Template>): List<IdentityEvidenceEngine.Template> {
        val sorted = input.sortedByDescending { it.face.qualityScore }; if (sorted.size <= MAX_TEMPLATES) return sorted
        val selected = sorted.take(4).toMutableList()
        for (candidate in sorted.drop(4)) {
            if (selected.size >= MAX_TEMPLATES) break
            val diverse = selected.none { a ->
                val ax = a.face.rotationX ?: 0f; val ay = a.face.rotationY ?: 0f; val az = a.face.rotationZ ?: 0f
                val bx = candidate.face.rotationX ?: 0f; val by = candidate.face.rotationY ?: 0f; val bz = candidate.face.rotationZ ?: 0f
                kotlin.math.abs(ax - bx) + kotlin.math.abs(ay - by) + kotlin.math.abs(az - bz) >= 18f
            }
            if (diverse) selected += candidate
        }
        if (selected.size < MAX_TEMPLATES) selected += sorted.filter { it !in selected }.take(MAX_TEMPLATES - selected.size)
        return selected
    }
    companion object { private const val MAX_TEMPLATES = 12 }
}
