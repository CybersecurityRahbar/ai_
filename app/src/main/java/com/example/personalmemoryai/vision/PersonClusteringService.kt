package com.example.personalmemoryai.vision

import android.content.Context
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.database.PersonEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Builds persistent visual person clusters from face observations.
 *
 * Phase 3 uses a multi-template prototype instead of comparing a new face
 * with only one representative face. Every existing observation belonging
 * to a person can contribute evidence, which makes the cluster more robust
 * to pose, illumination and image-quality changes.
 *
 * This remains an unsupervised visual grouping system. A cluster is a visual
 * hypothesis and must not be presented as proof of a real-world identity.
 */
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
        val faceIds: Set<Long>,
        val identityTemplates: List<EmbeddingEntity>,
        val shapeTemplates: List<EmbeddingEntity>
    )

    private data class Score(
        val composite: Float,
        val identity: Float,
        val shape: Float
    )

    suspend fun clusterUnassigned(): ClusterRunResult = withContext(Dispatchers.Default) {
        val run = diagnostics.begin("PERSON_CLUSTERING")
        try {
            val faces = database.faceDao().getUnassignedMatchableFaces()
            if (faces.isEmpty()) {
                run.success("No unassigned matchable faces", mapOf("processed" to "0"))
                return@withContext ClusterRunResult(0, 0, 0, 0, 0)
            }

            val allIdentityEmbeddings = database.embeddingDao().getAllForFaceSearch()
            val allShapeEmbeddings = database.embeddingDao()
                .getAllForOwnerType(FaceShapeEncoder.OWNER_TYPE)

            val identityByFace = allIdentityEmbeddings.associateBy { it.ownerId }
            val shapeByFace = allShapeEmbeddings.associateBy { it.ownerId }

            val existingPeople = database.personDao().getMostObserved()
            val candidates = existingPeople.mapNotNull { person ->
                val personFaces = database.faceDao().getByPersonId(person.id)
                val ids = personFaces.map { it.id }.toSet()
                val identityTemplates = ids.mapNotNull(identityByFace::get)
                val shapeTemplates = ids.mapNotNull(shapeByFace::get)
                if (identityTemplates.isEmpty() && shapeTemplates.isEmpty()) {
                    null
                } else {
                    Candidate(person, ids, identityTemplates, shapeTemplates)
                }
            }.toMutableList()

            var assigned = 0
            var created = 0
            var absorbed = 0
            var skipped = 0

            run.stage(
                "CLUSTER",
                "Grouping observations against multi-template person prototypes",
                mapOf(
                    "faces" to faces.size.toString(),
                    "existingClusters" to candidates.size.toString(),
                    "identityTemplates" to allIdentityEmbeddings.size.toString(),
                    "shapeTemplates" to allShapeEmbeddings.size.toString()
                )
            )

            for (face in faces.sortedByDescending { it.qualityScore }) {
                val identity = identityByFace[face.id]
                val shape = shapeByFace[face.id]
                val best = candidates.mapNotNull { candidate ->
                    score(identity, shape, candidate)?.let { candidate to it }
                }.maxByOrNull { it.second.composite }

                if (best != null && shouldAbsorb(best.second, face)) {
                    database.faceDao().assignToPerson(face.id, best.first.person.id)
                    refreshStatistics(best.first.person.id)
                    absorbed++
                    assigned++
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
                        modelVersion = "multi-template-v3"
                    )
                )
                database.faceDao().assignToPerson(face.id, personId)

                candidates += Candidate(
                    person = database.personDao().getById(personId)!!,
                    faceIds = setOf(face.id),
                    identityTemplates = listOfNotNull(identity),
                    shapeTemplates = listOfNotNull(shape)
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
                    "mode" to "multi-template-v3"
                )
            )

            ClusterRunResult(faces.size, assigned, created, absorbed, skipped)
        } catch (t: Throwable) {
            run.failure("CLUSTER", t)
            throw t
        }
    }

    /**
     * Scores a face against every usable template in the person cluster.
     * We use the strongest evidence plus a small second-best corroboration
     * term instead of averaging all templates, so an outlier does not dilute
     * a genuine match.
     */
    private fun score(
        identity: EmbeddingEntity?,
        shape: EmbeddingEntity?,
        candidate: Candidate
    ): Score? {
        val identityScores = if (identity != null) {
            candidate.identityTemplates.asSequence()
                .filter { it.dimension == identity.dimension && it.vector.isNotEmpty() }
                .map {
                    FaceSimilarity.cosineSimilarity(identity.vector, it.vector)
                        .coerceIn(0f, 1f)
                }
                .sortedDescending()
                .take(3)
                .toList()
        } else {
            emptyList()
        }

        val shapeScores = if (shape != null) {
            candidate.shapeTemplates.asSequence()
                .filter { it.dimension == shape.dimension && it.vector.isNotEmpty() }
                .map { FaceShapeEncoder.similarity(shape.vector, it.vector) }
                .sortedDescending()
                .take(3)
                .toList()
        } else {
            emptyList()
        }

        val identityScore = corroboratedTopScore(identityScores)
        val shapeScore = corroboratedTopScore(shapeScores)

        if (identityScore == null && shapeScore == null) return null

        val base = when {
            identityScore != null && shapeScore != null ->
                0.80f * identityScore + 0.20f * shapeScore
            identityScore != null -> identityScore
            else -> shapeScore!!
        }

        return Score(base.coerceIn(0f, 1f), identityScore ?: 0f, shapeScore ?: 0f)
    }

    /**
     * Strongest template dominates, while a second close template adds
     * corroboration. This is intentionally bounded to prevent overconfidence
     * when a cluster contains many near-duplicate observations.
     */
    private fun corroboratedTopScore(scores: List<Float>): Float? {
        if (scores.isEmpty()) return null
        val top = scores[0]
        if (scores.size == 1) return top
        val second = scores[1]
        return (0.84f * top + 0.16f * second).coerceIn(0f, 1f)
    }

    private fun shouldAbsorb(score: Score, face: FaceEntity): Boolean {
        val quality = face.qualityScore.coerceIn(0f, 1f)

        // Very strong identity evidence is allowed to survive lower image quality.
        if (score.identity >= 0.86f) return true

        // Strong identity plus independent facial-shape corroboration.
        if (score.identity >= 0.74f && score.shape >= 0.82f) return true

        // Shape-only grouping is deliberately conservative and requires a good face.
        if (score.identity == 0f && score.shape >= 0.94f && quality >= 0.72f) return true

        // Composite evidence with quality-aware margin.
        val qualityAdjustedThreshold = 0.88f - 0.04f * quality
        return score.composite >= max(0.84f, qualityAdjustedThreshold)
    }

    private suspend fun refreshStatistics(personId: Long) {
        val faces = database.faceDao().getByPersonId(personId)
        if (faces.isEmpty()) return

        val best = faces.maxByOrNull { it.qualityScore } ?: return
        val representativeEmbedding = database.embeddingDao()
            .getForOwnerAndModel("FACE", best.id, "MobileFaceNet", "1.0")
            ?: database.embeddingDao().getForOwner("FACE", best.id).firstOrNull()

        database.personDao().updateStatistics(
            personId = personId,
            faceCount = faces.size,
            bestQualityScore = faces.maxOf { it.qualityScore }.coerceIn(0f, 1f),
            hasRepresentativeEmbedding = representativeEmbedding != null,
            representativeFaceId = best.id
        )
    }
}
