package com.example.personalmemoryai.diagnostics

import android.content.Context
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.semantic.MobileClipModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for pipeline readiness.
 * It deliberately reports partial states instead of treating an empty index as healthy.
 */
class IntelligenceHealthService(context: Context) {
    private val appContext = context.applicationContext

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
        val diagnosticsEvents: Int,
        val errors: Int,
        val warnings: Int
    ) {
        val visualIndexReady: Boolean get() = modelInstalled && imageEmbeddings > 0
        val faceIndexReady: Boolean get() = faces > 0 && facesWithEmbedding > 0
        val faceCoverage: Int get() = if (faces == 0L) 0 else ((facesWithEmbedding * 100L) / faces).toInt().coerceIn(0, 100)
        val visualCoverage: Int get() = if (images == 0L) 0 else ((imageEmbeddings * 100L) / images).toInt().coerceIn(0, 100)
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
        Snapshot(
            images = db.imageDao().count(),
            faces = db.faceDao().count(),
            facesWithEmbedding = db.faceDao().countWithEmbeddings(),
            matchableFaces = db.faceDao().countMatchable(),
            people = db.personDao().count(),
            imageEmbeddings = db.embeddingDao().countByOwnerType("IMAGE"),
            faceEmbeddings = db.embeddingDao().countByOwnerType("FACE"),
            totalEmbeddings = db.embeddingDao().count(),
            modelInstalled = model.isInstalled(),
            modelSizeBytes = model.sizeBytes(),
            diagnosticsEvents = events.size,
            errors = events.count { it.contains("\"severity\":\"ERROR\"") || it.contains("\"severity\":\"CRITICAL\"") },
            warnings = events.count { it.contains("\"severity\":\"WARNING\"") }
        )
    }
}
