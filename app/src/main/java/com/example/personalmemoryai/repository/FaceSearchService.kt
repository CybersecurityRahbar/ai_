package com.example.personalmemoryai.repository

import android.graphics.Bitmap
import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.vision.FaceAnalysisService
import com.example.personalmemoryai.vision.FaceMatch
import com.example.personalmemoryai.vision.FaceMatchingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaceSearchService(
    private val analysisService: FaceAnalysisService,
    private val embeddingDao: EmbeddingDao,
    private val matchingEngine: FaceMatchingEngine =
        FaceMatchingEngine()
) {

    /**
     * Searches the local face index using a query image.
     *
     * If multiple faces are present in the query image,
     * each face becomes an independent search query.
     */
    suspend fun search(
        queryBitmap: Bitmap,
        limit: Int = 20
    ): SearchResult = withContext(
        Dispatchers.Default
    ) {

        val analyzed =
            analysisService.analyze(
                queryBitmap
            )

        if (analyzed.isEmpty()) {
            return@withContext SearchResult(
                facesFound = 0,
                matches = emptyMap()
            )
        }

        val storedEmbeddings =
            embeddingDao.getAllForFaceSearch()

        val candidates =
            storedEmbeddings.mapNotNull {

                if (
                    it.vector.isEmpty()
                ) {
                    return@mapNotNull null
                }

                val face =
                    it.face
                        ?: return@mapNotNull null

                FaceMatchingEngine.Candidate(

                    faceId =
                        face.id,

                    personId =
                        face.personId,

                    embedding =
                        it.vector,

                    qualityScore =
                        face.qualityScore
                )
            }

        val result =
            mutableMapOf<
                Int,
                List<FaceMatch>
            >()

        for (
            index in analyzed.indices
        ) {

            val query =
                analyzed[index]

            val embedding =
                query.embedding
                    ?: continue

            val matches =
                matchingEngine.rankCandidates(
                    queryEmbedding =
                        embedding,

                    candidates =
                        candidates,

                    limit =
                        limit
                )

            result[index] =
                matches
        }

        SearchResult(
            facesFound =
                analyzed.size,

            matches =
                result
        )
    }

    data class SearchResult(

        val facesFound: Int,

        val matches:
            Map<Int, List<FaceMatch>>
    )
}
