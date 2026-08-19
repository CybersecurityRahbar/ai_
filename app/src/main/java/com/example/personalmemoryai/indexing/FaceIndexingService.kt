package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import com.example.personalmemoryai.database.EmbeddingDao
import com.example.personalmemoryai.database.EmbeddingEntity
import com.example.personalmemoryai.database.FaceDao
import com.example.personalmemoryai.database.FaceEntity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.vision.FaceAnalysisService
import com.example.personalmemoryai.vision.FaceShapeEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FaceIndexingService(
    private val faceDao: FaceDao,
    private val embeddingDao: EmbeddingDao,
    private val analysisService: FaceAnalysisService,
    context: Context? = null
) {
    data class IndexResult(val imageId: Long, val detectedFaces: Int, val indexedFaces: Int, val rejectedFaces: Int, val modelEmbeddings: Map<String, Int> = emptyMap())
    private val diagnostics = context?.let { DiagnosticsManager.get(it) }

    suspend fun indexImage(imageId: Long, bitmap: Bitmap): IndexResult = withContext(Dispatchers.Default) {
        val run = diagnostics?.begin("FACE_INDEX", mapOf("imageId" to imageId.toString()))
        try {
            run?.stage("DETECT", "Running face detector, landmarks, pose, quality and all configured identity models")
            val analyzedFaces = analysisService.analyze(bitmap)
            run?.stage("DETECTION_RESULT", "Face analysis completed", mapOf("faces" to analyzedFaces.size.toString()))
            var indexed = 0
            var rejected = 0
            val modelCounts = linkedMapOf<String, Int>()
            for ((index, face) in analyzedFaces.withIndex()) {
                try {
                    val box = face.detection.boundingBox
                    val shape = face.landmarkShape
                    val faceEntity = FaceEntity(
                        imageId = imageId,
                        boundingLeft = box.left,
                        boundingTop = box.top,
                        boundingRight = box.right,
                        boundingBottom = box.bottom,
                        detectionConfidence = face.detection.detectionConfidence,
                        qualityScore = face.quality.score,
                        rotationX = face.detection.rotationX,
                        rotationY = face.detection.rotationY,
                        rotationZ = face.detection.rotationZ,
                        hasEmbedding = face.embeddings.isNotEmpty(),
                        hasLandmarks = face.detection.landmarks.isNotEmpty(),
                        landmarkCount = face.detection.landmarks.size,
                        isOccluded = detectOcclusion(face),
                        usableForMatching = face.usableForMatching,
                        analyzedAt = System.currentTimeMillis(),
                        analyzerVersion = buildAnalyzerVersion(face)
                    )
                    val faceId = faceDao.insert(faceEntity)
                    for ((_, modelEmbedding) in face.embeddings) {
                        embeddingDao.insert(EmbeddingEntity(OWNER_FACE, faceId, modelEmbedding.vector, modelEmbedding.vector.size, modelEmbedding.modelName, modelEmbedding.modelVersion, true, System.currentTimeMillis()))
                        modelCounts[modelEmbedding.modelName] = (modelCounts[modelEmbedding.modelName] ?: 0) + 1
                    }
                    if (shape != null && shape.isNotEmpty()) {
                        embeddingDao.insert(EmbeddingEntity(FaceShapeEncoder.OWNER_TYPE, faceId, shape, shape.size, FaceShapeEncoder.MODEL_NAME, FaceShapeEncoder.MODEL_VERSION, true, System.currentTimeMillis()))
                    }
                    if (face.embeddings.isNotEmpty() && shape != null) {
                        indexed++
                        run?.stage("FACE_$index", "Face signatures persisted", mapOf("faceId" to faceId.toString(), "models" to face.embeddings.keys.joinToString(","), "modelCount" to face.embeddings.size.toString(), "shapeDimension" to shape.size.toString(), "quality" to "%.3f".format(java.util.Locale.US, face.quality.score), "modelErrors" to face.embeddingErrors.size.toString()))
                    } else {
                        rejected++
                        run?.warning("Face detected but no complete matching signature was produced", mapOf("faceIndex" to index.toString(), "modelErrors" to face.embeddingErrors.entries.joinToString(";") { "${it.key}:${it.value}" }, "modelsProduced" to face.embeddings.keys.joinToString(","), "hasShapeSignature" to (shape != null).toString()))
                    }
                } catch (t: Throwable) {
                    rejected++
                    run?.failure("FACE_$index", t)
                }
            }
            val result = IndexResult(imageId, analyzedFaces.size, indexed, rejected, modelCounts)
            run?.success("Face indexing completed", mapOf("detected" to result.detectedFaces.toString(), "indexed" to indexed.toString(), "rejected" to rejected.toString(), "modelEmbeddings" to modelCounts.entries.joinToString(",") { "${it.key}=${it.value}" }))
            result
        } catch (t: Throwable) {
            run?.failure("PIPELINE", t)
            throw t
        }
    }

    suspend fun removeImageIndex(imageId: Long) {
        val faces = faceDao.getByImageId(imageId)
        for (face in faces) {
            embeddingDao.deleteForOwner(OWNER_FACE, face.id)
            embeddingDao.deleteForOwner(FaceShapeEncoder.OWNER_TYPE, face.id)
        }
        faceDao.deleteByImageId(imageId)
    }

    private fun detectOcclusion(face: FaceAnalysisService.AnalyzedFace): Boolean = face.quality.score < 0.35f
    private fun buildAnalyzerVersion(face: FaceAnalysisService.AnalyzedFace): String = "vision-3.0/${face.embeddings.values.joinToString(",") { "${it.modelName}:${it.modelVersion}:${it.vector.size}" }}/${FaceShapeEncoder.MODEL_VERSION}"
    companion object { private const val OWNER_FACE = "FACE" }
}
