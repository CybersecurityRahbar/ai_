package com.example.personalmemoryai.vision

import kotlin.math.max

class FaceMatchingEngine {

    /**
     * Finds the closest stored embeddings to a query vector.
     *
     * This method intentionally does NOT decide identity by itself.
     * It returns ranked candidates.
     *
     * Identity decisions can then use:
     *
     * - similarity
     * - quality
     * - number of supporting images
     * - model version
     * - user confirmation
     */
    fun rankCandidates(
        queryEmbedding: FloatArray,
        candidates: List<Candidate>,
        limit: Int = 20
    ): List<FaceMatch> {

        if (
            queryEmbedding.isEmpty() ||
            candidates.isEmpty()
        ) {
            return emptyList()
        }

        val ranked =
            candidates.mapNotNull { candidate ->

                if (
                    candidate.embedding.isEmpty() ||
                    candidate.embedding.size !=
                    queryEmbedding.size
                ) {
                    return@mapNotNull null
                }

                val similarity =
                    FaceSimilarity.cosineSimilarity(
                        queryEmbedding,
                        candidate.embedding
                    )

                val distance =
                    FaceSimilarity.euclideanDistance(
                        queryEmbedding,
                        candidate.embedding
                    )

                val qualityWeight =
                    max(
                        0.1f,
                        candidate.qualityScore
                    )

                /*
                 * Quality affects ranking, but does not replace
                 * the actual embedding similarity.
                 */
                val rankingScore =
                    similarity *
                        (
                            0.75f +
                            0.25f * qualityWeight
                        )

                RankedCandidate(
                    candidate = candidate,
                    similarity = similarity,
                    distance = distance,
                    rankingScore = rankingScore
                )
            }
                .sortedByDescending {
                    it.rankingScore
                }
                .take(
                    limit.coerceAtLeast(1)
                )

        return ranked.mapIndexed { index, item ->

            FaceMatch(
                faceId =
                    item.candidate.faceId,

                personId =
                    item.candidate.personId,

                similarity =
                    item.similarity,

                distance =
                    item.distance,

                qualityScore =
                    item.candidate.qualityScore,

                rank =
                    index + 1
            )
        }
    }

    data class Candidate(

        val faceId: Long,

        val personId: Long?,

        val embedding: FloatArray,

        val qualityScore: Float
    )

    private data class RankedCandidate(

        val candidate: Candidate,

        val similarity: Float,

        val distance: Float,

        val rankingScore: Float
    )
}
