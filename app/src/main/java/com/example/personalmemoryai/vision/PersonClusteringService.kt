package com.example.personalmemoryai.vision

import android.content.Context
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.database.PersonEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistent visual person clustering. */
class PersonClusteringService(context: Context) {
    private val database = AppDatabase.getInstance(context.applicationContext)
    private val diagnostics = DiagnosticsManager.get(context.applicationContext)

    data class ClusterRunResult(
        val processedFaces: Int,
        val assignedFaces: Int,
        val newClusters: Int,
        val absorbedIntoExisting: Int,
        val skipped: Int
    )

    private data class Candidate(
        val person: PersonEntity,
        val templates: List<IdentityEvidenceEngine.Template>
    )

    suspend fun clusterUnassigned(): ClusterRunResult = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("PERSON_CLUSTERING")
        try {
            val faces = database.faceDao().getUnassignedMatchableFaces()
            if (faces.isEmpty()) return@withContext ClusterRunResult(0, 0, 0, 0, 0)

            val allIdentity = database.embeddingDao().getAllForFaceSearch()
                .filter { it.modelName.equals("mobilefacenet", true) }
                .groupBy { it.ownerId }
                .mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }
            val allShape = database.embeddingDao().getAllForOwnerType(FaceShapeEncoder.OWNER_TYPE)
                .groupBy { it.ownerId }
                .mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }

            val candidates = database.personDao().getMostObserved().mapNotNull { person ->
                val templates = database.faceDao().getByPersonId(person.id).mapNotNull { template(it, allIdentity, allShape) }
                if (templates.isEmpty()) null else Candidate(person, selectDiverseTemplates(templates))
            }.toMutableList()

            var assigned = 0
            var created = 0
            var absorbed = 0
            var skipped = 0

            for (face in faces.sortedWith(compareByDescending<FaceEntity> { it.qualityScore }.thenBy { it.id })) {
                val identity = allIdentity[face.id]
                val shape = allShape[face.id]
                if (identity == null && shape == null) {
                    skipped++
                    continue
                }

                val best = candidates.mapNotNull { candidate ->
                    IdentityEvidenceEngine.compare(face, identity, shape, candidate.templates)?.let { candidate to it }
                }.maxByOrNull { it.second.composite }

                if (best != null && IdentityEvidenceEngine.shouldAssociate(best.second)) {
                    database.faceDao().assignToPerson(face.id, best.first.person.id)
                    refreshStatistics(best.first.person.id)
                    absorbed++
                    assigned++
                    val updatedFaces = database.faceDao().getByPersonId(best.first.person.id)
                    val updatedTemplates = updatedFaces.mapNotNull { template(it, allIdentity, allShape) }
                    val index = candidates.indexOf(best.first)
                    if (index >= 0) candidates[index] = best.first.copy(templates = selectDiverseTemplates(updatedTemplates))
                } else {
                    val personId = database.personDao().insert(
                        PersonEntity(
                            description = "Auto-clustered visual observations",
                            faceCount = 1,
                            bestQualityScore = face.qualityScore.coerceIn(0f, 1f),
                            hasRepresentativeEmbedding = identity != null,
                            representativeFaceId = face.id,
                            modelVersion = "identity-evidence-v4"
                        )
                    )
                    database.faceDao().assignToPerson(face.id, personId)
                    val person = database.personDao().getById(personId)!!
                    candidates += Candidate(person, listOfNotNull(template(face, allIdentity, allShape)))
                    assigned++
                    created++
                }
            }

            candidates.forEach { refreshStatistics(it.person.id) }
            run.success("Person clustering completed", mapOf("processed" to faces.size.toString(), "assigned" to assigned.toString(), "newClusters" to created.toString(), "absorbed" to absorbed.toString(), "skipped" to skipped.toString()))
            ClusterRunResult(faces.size, assigned, created, absorbed, skipped)
        } catch (t: Throwable) {
            run.failure("CLUSTER", t)
            throw t
        }
    }

    private fun template(face: FaceEntity, identity: Map<Long, com.example.personalmemoryai.database.EmbeddingEntity>, shape: Map<Long, com.example.personalmemoryai.database.EmbeddingEntity>): IdentityEvidenceEngine.Template? {
        if (!face.usableForMatching) return null
        val embedding = identity[face.id]
        val shapeEmbedding = shape[face.id]
        if (embedding == null && shapeEmbedding == null) return null
        return IdentityEvidenceEngine.Template(face, embedding, shapeEmbedding)
    }

    private fun selectDiverseTemplates(templates: List<IdentityEvidenceEngine.Template>): List<IdentityEvidenceEngine.Template> {
        if (templates.size <= MAX_TEMPLATES) return templates.sortedByDescending { it.face.qualityScore }
        val sorted = templates.sortedByDescending { it.face.qualityScore }
        val selected = mutableListOf<IdentityEvidenceEngine.Template>()
        for (candidate in sorted) {
            if (selected.size >= MAX_TEMPLATES) break
            if (selected.size < 4) { selected += candidate; continue }
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

    private suspend fun refreshStatistics(personId: Long) {
        val faces = database.faceDao().getByPersonId(personId)
        if (faces.isEmpty()) return
        val best = faces.maxByOrNull { it.qualityScore } ?: return
        val representativeEmbedding = database.embeddingDao().getForOwnerAndModel("FACE", best.id, "mobilefacenet", "tflite")
            ?: database.embeddingDao().getForOwner("FACE", best.id).firstOrNull()
        database.personDao().updateStatistics(personId, faces.size, faces.maxOf { it.qualityScore }.coerceIn(0f, 1f), representativeEmbedding != null, best.id)
    }

    companion object { private const val MAX_TEMPLATES = 12 }
}
