package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceDao
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.vision.FaceAnalysisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaceIndexingService(
    private val faceDao: FaceDao,
    private val embeddingDao: EmbeddingDao,
    private val analysisService: FaceAnalysisService,
    context: Context? = null
) {
    data class IndexResult(val imageId: Long, val detectedFaces: Int, val indexedFaces: Int, val rejectedFaces: Int)
    private val diagnostics = context?.let { DiagnosticsManager.get(it) }

    suspend fun indexImage(imageId: Long, bitmap: Bitmap): IndexResult = withContext(Dispatchers.Default) {
        val run = diagnostics?.begin("FACE_INDEX", mapOf("imageId" to imageId.toString()))
        try {
            run?.stage("DETECT", "Running face detector and landmark analysis")
            val analyzedFaces = analysisService.analyze(bitmap)
            run?.stage("DETECTION_RESULT", "Face analysis completed", mapOf("faces" to analyzedFaces.size.toString()))
            var indexed = 0
            var rejected = 0
            for ((index, face) in analyzedFaces.withIndex()) {
                try {
                    val box = face.detection.boundingBox
                    val embedding = face.embedding
                    val faceEntity = FaceEntity(imageId = imageId, boundingLeft = box.left, boundingTop = box.top, boundingRight = box.right, boundingBottom = box.bottom, detectionConfidence = face.detection.detectionConfidence, qualityScore = face.quality.score, hasEmbedding = embedding != null, hasLandmarks = true, landmarkCount = face.detection.landmarks.size, isOccluded = detectOcclusion(face), usableForMatching = face.usableForMatching, analyzedAt = System.currentTimeMillis(), analyzerVersion = buildAnalyzerVersion(face))
                    val faceId = faceDao.insert(faceEntity)
                    if (embedding != null && embedding.isNotEmpty()) {
                        embeddingDao.insert(EmbeddingEntity(ownerType = OWNER_FACE, ownerId = faceId, vector = embedding, dimension = embedding.size, modelName = face.embeddingModelName ?: "unknown", modelVersion = face.embeddingModelVersion ?: "unknown", normalized = true, createdAt = System.currentTimeMillis()))
                        indexed++
                        run?.stage("FACE_$index", "Face embedding persisted", mapOf("faceId" to faceId.toString(), "dimension" to embedding.size.toString(), "quality" to "%.3f".format(java.util.Locale.US, face.quality.score)))
                    } else {
                        rejected++
                        run?.warning("Face detected but no embedding was produced", mapOf("faceIndex" to index.toString(), "quality" to face.quality.score.toString()))
                    }
                } catch (t: Throwable) {
                    rejected++
                    run?.failure("FACE_$index", t)
                }
            }
            val result = IndexResult(imageId, analyzedFaces.size, indexed, rejected)
            run?.success("Face indexing completed", mapOf("detected" to result.detectedFaces.toString(), "indexed" to indexed.toString(), "rejected" to rejected.toString()))
            result
        } catch (t: Throwable) {
            run?.failure("PIPELINE", t)
            throw t
        }
    }

    suspend fun removeImageIndex(imageId: Long) {
        val faces = faceDao.getByImageId(imageId)
        for (face in faces) embeddingDao.deleteForOwner(OWNER_FACE, face.id)
        faceDao.deleteByImageId(imageId)
    }

    private fun detectOcclusion(face: FaceAnalysisService.AnalyzedFace): Boolean = face.quality.score < 0.35f

    private fun buildAnalyzerVersion(face: FaceAnalysisService.AnalyzedFace): String {
        val model = face.embeddingModelName ?: "none"
        val version = face.embeddingModelVersion ?: "none"
        return "vision-1.0/$model/$version"
    }

    companion object { private const val OWNER_FACE = "FACE" }
}
