package com.example.personalmemoryai.vision

import android.content.Context
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.database.PersonEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent visual person clustering.
 *
 * Phase 3 is deliberately conservative: a PersonEntity is a visual cluster
 * hypothesis, not proof of a real-world identity. Association uses multiple
 * face templates and independent signals rather than a single representative.
 */
class PersonClusteringService(context: Context) {
    private val database = AppDatabase.getInstance(context.applicationContext)
    private val diagnostics = DiagnosticsManager(context.applicationContext)

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
            if (faces.isEmpty()) {
                run.success("No unassigned matchable faces", mapOf("processed" to "0"))
                return@withContext ClusterRunResult(0, 0, 0, 0, 0)
            }

            val allIdentity = database.embeddingDao().getAllForFaceSearch()
                .filter { it.modelName.equals("mobilefacenet", true) }
                .groupBy { it.ownerId }
                .mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }
            val allShape = database.embeddingDao()
                .getAllForOwnerType(FaceShapeEncoder.OWNER_TYPE)
                .groupBy { it.ownerId }
                .mapValues { (_, values) -> values.maxByOrNull { it.createdAt }!! }

            val allFacesById = mutableMapOf<Long, FaceEntity>()
            database.personDao().getMostObserved().forEach { person ->
                database.faceDao().getByPersonId(person.id).forEach { allFacesById[it.id] = it }
            }
            faces.forEach { allFacesById[it.id] = it }

            val candidates = database.personDao().getMostObserved().mapNotNull { person ->
                val templates = database.faceDao().getByPersonId(person.id).mapNotNull { face ->
                    if (face.usableForMatching.not()) return@mapNotNull null
                    val identity = allIdentity[face.id]
                    val shape = allShape[face.id]
                    if (identity == null && shape == null) null
                    else IdentityEvidenceEngine.Template(face, identity, shape)
                }
                if (templates.isEmpty()) null else Candidate(person, selectDiverseTemplates(templates))
            }.toMutableList()

            var assigned = 0
            var created = 0
            var absorbed = 0
            var skipped = 0

            run.stage(
                "CLUSTER",
                "Multi-template visual identity clustering",
                mapOf(
                    "faces" to faces.size.toString(),
                    "existingClusters" to candidates.size.toString(),
                    "identityTemplates" to allIdentity.size.toString(),
                    "shapeTemplates" to allShape.size.toString()
                )
            )

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
                    val updatedTemplates = updatedFaces.mapNotNull { f ->
                        if (!f.usableForMatching) return@mapNotNull null
                        val e = allIdentity[f.id]
                        val s = allShape[f.id]
                        if (e == null && s == null) null else IdentityEvidenceEngine.Template(f, e, s)
                    }
                    val index = candidates.indexOf(best.first)
                    if (index >= 0) candidates[index] = best.first.copy(templates = selectDiverseTemplates(updatedTemplates))
                    continue
                }

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
                candidates += Candidate(
                    person,
                    listOf(IdentityEvidenceEngine.Template(face, identity, shape))
                )
                assigned++
                created++
            }

            candidates.forEach { refreshStatistics(it.person.id) }

            run.success(
                "Person clustering completed",
                mapOf(
                    "processed" to faces.size.toString(),
                    "assigned" to assigned.toString(),
                    "newClusters" to created.toString(),
                    "absorbed" to absorbed.toString(),
                    "skipped" to skipped.toString(),
                    "mode" to "multi-template-evidence-v4"
                )
            )

            ClusterRunResult(faces.size, assigned, created, absorbed, skipped)
        } catch (t: Throwable) {
            run.failure("CLUSTER", t)
            throw t
        }
    }

    /**
     * Keeps a bounded, quality-aware template bank. The bank deliberately
     * favors different head poses so one pose cannot dominate a cluster.
     */
    private fun selectDiverseTemplates(templates: List<IdentityEvidenceEngine.Template>): List<IdentityEvidenceEngine.Template> {
        if (templates.size <= MAX_TEMPLATES) return templates.sortedByDescending { it.face.qualityScore }
        val sorted = templates.sortedByDescending { it.face.qualityScore }
        val selected = mutableListOf<IdentityEvidenceEngine.Template>()
        for (candidate in sorted) {
            if (selected.size >= MAX_TEMPLATES) break
            val sufficientlyDifferentPose = selected.none { a ->
                val ax = a.face.rotationX ?: 0f
                val ay = a.face.rotationY ?: 0f
                val az = a.face.rotationZ ?: 0f
                val bx = candidate.face.rotationX ?: 0f
                val by = candidate.face.rotationY ?: 0f
                val bz = candidate.face.rotationZ ?: 0f
                kotlin.math.abs(ax - bx) + kotlin.math.abs(ay - by) + kotlin.math.abs(az - bz) >= 18f
            }
            if (selected.isEmpty() || !sufficientlyDifferentPose || selected.size < 4) selected += candidate
        }
        return selected.take(MAX_TEMPLATES)
    }

    private suspend fun refreshStatistics(personId: Long) {
        val faces = database.faceDao().getByPersonId(personId)
        if (faces.isEmpty()) return
        val best = faces.maxByOrNull { it.qualityScore } ?: return
        val representativeEmbedding = database.embeddingDao()
            .getForOwnerAndModel("FACE", best.id, "mobilefacenet", "tflite")
            ?: database.embeddingDao().getForOwner("FACE", best.id).firstOrNull()
        database.personDao().updateStatistics(
            personId = personId,
            faceCount = faces.size,
            bestQualityScore = faces.maxOf { it.qualityScore }.coerceIn(0f, 1f),
            hasRepresentativeEmbedding = representativeEmbedding != null,
            representativeFaceId = best.id
        )
    }

    companion object {
        private const val MAX_TEMPLATES = 12
    }
}
