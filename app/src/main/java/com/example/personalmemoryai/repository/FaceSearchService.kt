package com.example.personalmemoryai.repository

import android.graphics.Bitmap
import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.database.FaceDao
import com.example.personalmemoryai.vision.FaceAnalysisService
import com.example.personalmemoryai.vision.FaceMatch
import com.example.personalmemoryai.vision.FaceMatchingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaceSearchService(
    private val analysisService: FaceAnalysisService,
    private val embeddingDao: EmbeddingDao,
    private val faceDao: FaceDao,
    private val matchingEngine:
        FaceMatchingEngine =
            FaceMatchingEngine()
) {

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
            embeddingDao
                .getAllForFaceSearch()

        val candidates =
            storedEmbeddings.mapNotNull { embedding ->

                val face =
                    faceDao.getById(
                        embedding.ownerId
                    )
                        ?: return@mapNotNull null

                if (
                    embedding.vector.isEmpty()
                ) {
                    return@mapNotNull null
                }

                FaceMatchingEngine.Candidate(

                    faceId =
                        face.id,

                    personId =
                        face.personId,

                    embedding =
                        embedding.vector,

                    qualityScore =
                        face.qualityScore
                )
            }

        val matchesByQueryFace =
            mutableMapOf<
                Int,
                List<FaceMatch>
            >()

        for (
            index in analyzed.indices
        ) {

            val queryFace =
                analyzed[index]

            val embedding =
                queryFace.embedding
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

            matchesByQueryFace[index] =
                matches
        }

        SearchResult(
            facesFound =
                analyzed.size,

            matches =
                matchesByQueryFace
        )
    }

    data class SearchResult(

        val facesFound: Int,

        val matches:
            Map<Int, List<FaceMatch>>
    )
}
