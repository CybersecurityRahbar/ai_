package com.example.personalmemoryai.diagnostics

import android.content.Context
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.semantic.MobileClipModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Single source of truth for intelligence pipeline readiness and coverage. */
class IntelligenceHealthService(context: Context) {
    private val appContext = context.applicationContext

    enum class Status { ONLINE, DEGRADED, OFFLINE, NOT_READY }

    data class PipelineStage(
        val name: String,
        val status: Status,
        val processed: Long,
        val successful: Long,
        val coveragePercent: Int,
        val detail: String
    )

    data class EngineHealth(
        val name: String,
        val status: Status,
        val lastEvent: String,
        val lastTimestamp: Long,
        val lastLatencyMs: Long,
        val lastError: String
    )

    data class Snapshot(
        val images: Long,
        val faces: Long,
        val facesWithEmbedding: Long,
        val matchableFaces: Long,
        val people: Long,
        val imageEmbeddings: Long,
        val faceEmbeddings: Long,
        val totalEmbeddings: Long,
        val modelInstalled: Boolean,
        val modelSizeBytes: Long,
        val ocrAttempted: Long,
        val imagesWithOcr: Long,
        val averageOcrQuality: Float,
        val imagesWithObjects: Long,
        val diagnosticsEvents: Int,
        val errors: Int,
        val warnings: Int,
        val stages: List<PipelineStage>,
        val engines: List<EngineHealth> = emptyList()
    ) {
        val visualIndexReady get() = modelInstalled && imageEmbeddings > 0
        val faceIndexReady get() = faces > 0 && facesWithEmbedding > 0
        val faceCoverage get() = if (faces == 0L) 0 else ((facesWithEmbedding * 100L) / faces).toInt().coerceIn(0, 100)
        val visualCoverage get() = if (images == 0L) 0 else ((imageEmbeddings * 100L) / images).toInt().coerceIn(0, 100)
        val ocrCoverage get() = if (images == 0L) 0 else ((imagesWithOcr * 100L) / images).toInt().coerceIn(0, 100)
        val objectCoverage get() = if (images == 0L) 0 else ((imagesWithObjects * 100L) / images).toInt().coerceIn(0, 100)
        val overall: String
            get() = when {
                errors > 0 && images == 0L -> "CRITICAL"
                images == 0L -> "EMPTY"
                errors > 0 -> "DEGRADED"
                visualIndexReady && faceIndexReady -> "READY"
                else -> "PARTIAL"
            }
    }

    suspend fun snapshot(): Snapshot = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(appContext)
        val diagnostics = DiagnosticsManager.get(appContext)
        val events = diagnostics.readLatest(5000)
        val model = MobileClipModelManager(appContext)
        val images = db.imageDao().count().toLong()
        val faces = db.faceDao().count()
        val facesWithEmbedding = db.faceDao().countWithEmbeddings()
        val imageEmbeddings = db.embeddingDao().countByOwnerType("IMAGE")
        val faceEmbeddings = db.embeddingDao().countByOwnerType("FACE")
        val ocrAttempted = db.imageDao().countOcrAttempted()
        val imagesWithOcr = db.imageDao().countWithOcr()
        val objects = db.imageDao().countWithDetectedObjects()
        val avgOcr = db.imageDao().averageOcrQuality() ?: 0f
        val errors = events.count { it.contains("\"severity\":\"ERROR\"") || it.contains("\"severity\":\"CRITICAL\"") }
        val warnings = events.count { it.contains("\"severity\":\"WARNING\"") }
        val modelReady = model.isInstalled()
        val stages = listOf(
            PipelineStage("IMAGE_DECODE", if (images > 0) Status.ONLINE else Status.NOT_READY, images, images, 100, "$images images persisted"),
            PipelineStage("OCR", stageStatus(imagesWithOcr, images, errors), ocrAttempted, imagesWithOcr, coverage(imagesWithOcr, images), "avg quality=${"%.1f".format(avgOcr)}"),
            PipelineStage("FACE_EMBEDDING", stageStatus(facesWithEmbedding, faces, errors), faces, facesWithEmbedding, coverage(facesWithEmbedding, faces), "$facesWithEmbedding/$faces faces have embeddings"),
            PipelineStage("MOBILECLIP", if (!modelReady) Status.NOT_READY else stageStatus(imageEmbeddings, images, errors), images, imageEmbeddings, coverage(imageEmbeddings, images), if (modelReady) "model imported; index coverage is measured separately" else "model not installed"),
            PipelineStage("OBJECTS", stageStatus(objects, images, errors), images, objects, coverage(objects, images), "$objects images contain persisted object data"),
            PipelineStage("PERSISTENCE", if (images > 0) Status.ONLINE else Status.NOT_READY, images, images, 100, "Room database reachable")
        )
        val engines = buildEngineHealth(events)
        Snapshot(
            images, faces, facesWithEmbedding, db.faceDao().countMatchable(), db.personDao().count(),
            imageEmbeddings, faceEmbeddings, db.embeddingDao().count(), modelReady, model.installedSizeBytes(),
            ocrAttempted, imagesWithOcr, avgOcr, objects, events.size, errors, warnings, stages, engines
        )
    }

    private fun buildEngineHealth(events: List<String>): List<EngineHealth> {
        val names = listOf("MobileCLIP-S2", "MobileFaceNet", "FaceNet-512", "Facial Landmarks", "Arabic OCR", "Object Detection")
        return names.map { name ->
            val componentEvents = events.mapNotNull { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }.filter { json ->
                val metadata = json.optJSONObject("metadata")
                metadata?.optString("component").equals(name, ignoreCase = true) ||
                    json.optString("message").contains(name, ignoreCase = true)
            }
            val latest = componentEvents.lastOrNull()
            if (latest == null) {
                EngineHealth(name, Status.NOT_READY, "NO_RUNTIME_TELEMETRY", 0L, 0L, "")
            } else {
                val severity = latest.optString("severity")
                val stage = latest.optString("stage")
                val metadata = latest.optJSONObject("metadata")
                val latency = metadata?.optString("latencyMs")?.toLongOrNull() ?: 0L
                val status = when {
                    severity == "CRITICAL" -> Status.OFFLINE
                    severity == "ERROR" || stage.contains("FAILURE") || stage.contains("INVALID") -> Status.DEGRADED
                    stage == "INFERENCE_SUCCESS" || stage == "LOAD_SUCCESS" -> Status.ONLINE
                    severity == "WARNING" -> Status.DEGRADED
                    else -> Status.NOT_READY
                }
                EngineHealth(
                    name,
                    status,
                    stage.ifBlank { "UNKNOWN" },
                    latest.optLong("timestamp", 0L),
                    latency,
                    if (severity == "ERROR" || severity == "CRITICAL") latest.optString("cause").ifBlank { latest.optString("message") } else ""
                )
            }
        }
    }

    private fun coverage(successful: Long, processed: Long): Int = if (processed <= 0) 0 else ((successful * 100L) / processed).toInt().coerceIn(0, 100)

    private fun stageStatus(successful: Long, processed: Long, errors: Int): Status = when {
        processed == 0L -> Status.NOT_READY
        successful == processed && errors == 0 -> Status.ONLINE
        successful > 0L -> Status.DEGRADED
        else -> Status.OFFLINE
    }
}
