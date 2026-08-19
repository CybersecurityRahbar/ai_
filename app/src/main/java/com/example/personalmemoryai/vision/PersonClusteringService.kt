package com.example.personalmemoryai.vision

import android.content.Context
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.database.PersonEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Incrementally groups unassigned face observations into visual person clusters. */
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
        val identity: EmbeddingEntity?,
        val shape: EmbeddingEntity?
    )

    private data class Score(val composite: Float, val identity: Float, val shape: Float)

    suspend fun clusterUnassigned(): ClusterRunResult = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("PERSON_CLUSTERING")
        try {
            val faces = database.faceDao().getUnassignedMatchableFaces()
            if (faces.isEmpty()) {
                run.success("No unassigned matchable faces", mapOf("processed" to "0"))
                return@withContext ClusterRunResult(0, 0, 0, 0, 0)
            }

            val identityMap = database.embeddingDao().getAllForFaceSearch().associateBy { it.ownerId }
            val shapeMap = database.embeddingDao()
                .getAllForOwnerType(FaceShapeEncoder.OWNER_TYPE)
                .associateBy { it.ownerId }

            val candidates = database.personDao().getMostObserved().mapNotNull { person ->
                val id = person.representativeFaceId ?: return@mapNotNull null
                val identity = identityMap[id]
                val shape = shapeMap[id]
                if (identity == null && shape == null) null else Candidate(person, identity, shape)
            }.toMutableList()

            var assigned = 0
            var created = 0
            var absorbed = 0
            var skipped = 0
            run.stage("CLUSTER", "Grouping unassigned face observations", mapOf("faces" to faces.size.toString()))

            for (face in faces.sortedByDescending { it.qualityScore }) {
                val identity = identityMap[face.id]
                val shape = shapeMap[face.id]
                val best = candidates.mapNotNull { c -> score(identity, shape, c)?.let { c to it } }
                    .maxByOrNull { it.second.composite }

                if (best != null && shouldAbsorb(best.second, face)) {
                    database.faceDao().assignToPerson(face.id, best.first.person.id)
                    refreshStatistics(best.first.person.id)
                    assigned++
                    absorbed++
                    continue
                }

                if (identity == null && shape == null) {
                    skipped++
                    continue
                }

                val personId = database.personDao().insert(
                    PersonEntity(
                        description = "Auto-clustered visual observations",
                        faceCount = 1,
                        bestQualityScore = face.qualityScore.coerceIn(0f, 1f),
                        hasRepresentativeEmbedding = identity != null,
                        representativeFaceId = face.id,
                        modelVersion = "multi-signal-v2"
                    )
                )
                database.faceDao().assignToPerson(face.id, personId)
                candidates += Candidate(
                    database.personDao().getById(personId)!!,
                    identity,
                    shape
                )
                assigned++
                created++
            }

            candidates.forEach { refreshStatistics(it.person.id) }
            run.success(
                "Person clustering completed",
                mapOf("processed" to faces.size.toString(), "assigned" to assigned.toString(),
                    "newClusters" to created.toString(), "absorbed" to absorbed.toString(),
                    "skipped" to skipped.toString())
            )
            ClusterRunResult(faces.size, assigned, created, absorbed, skipped)
        } catch (t: Throwable) {
            run.failure("CLUSTER", t)
            throw t
        }
    }

    private fun score(identity: EmbeddingEntity?, shape: EmbeddingEntity?, candidate: Candidate): Score? {
        val identityScore = if (identity != null && candidate.identity != null &&
            identity.dimension == candidate.identity.dimension) {
            FaceSimilarity.cosineSimilarity(identity.vector, candidate.identity.vector).coerceIn(0f, 1f)
        } else null
        val shapeScore = if (shape != null && candidate.shape != null &&
            shape.dimension == candidate.shape.dimension) {
            FaceShapeEncoder.similarity(shape.vector, candidate.shape.vector)
        } else null
        if (identityScore == null && shapeScore == null) return null
        val base = when {
            identityScore != null && shapeScore != null -> 0.78f * identityScore + 0.22f * shapeScore
            identityScore != null -> identityScore
            else -> shapeScore!!
        }
        return Score(base.coerceIn(0f, 1f), identityScore ?: 0f, shapeScore ?: 0f)
    }

    private fun shouldAbsorb(score: Score, face: FaceEntity): Boolean {
        if (score.identity >= 0.84f) return true
        if (score.identity >= 0.72f && score.shape >= 0.82f) return true
        if (score.identity == 0f && score.shape >= 0.93f && face.qualityScore >= 0.70f) return true
        return score.composite >= 0.86f
    }

    private suspend fun refreshStatistics(personId: Long) {
        val faces = database.faceDao().getByPersonId(personId)
        if (faces.isEmpty()) return
        val best = faces.maxByOrNull { it.qualityScore } ?: return
        val representativeEmbedding = database.embeddingDao().getAllForFaceSearch()
            .firstOrNull { it.ownerId == best.id }
        database.personDao().updateStatistics(
            personId = personId,
            faceCount = faces.size,
            bestQualityScore = faces.maxOf { it.qualityScore }.coerceIn(0f, 1f),
            hasRepresentativeEmbedding = representativeEmbedding != null,
            representativeFaceId = best.id
        )
    }
}
