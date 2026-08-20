package com.example.personalmemoryai.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.databinding.ActivityImageViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase-7 evidence dossier.
 *
 * This screen is intentionally evidence-first: it presents the persisted
 * image record and the intelligence already produced for that image.
 * It does not run a new model inference and it has no TextEncoder dependency.
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString.isNullOrBlank()) {
            finish()
            return
        }

        val uri = Uri.parse(uriString)
        try {
            binding.imageView.setImageURI(uri)
        } catch (_: Throwable) {
            binding.headerStatus.text = "● IMAGE ERROR"
            binding.headerStatus.setTextColor(0xFFFF304F.toInt())
        }

        binding.closeButton.setOnClickListener { finish() }
        loadEvidence(uriString)
    }

    private fun loadEvidence(uri: String) {
        lifecycleScope.launch {
            val record = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(applicationContext).imageDao().findByUri(uri)
            }

            if (record == null) {
                binding.headerTitle.text = "UNINDEXED IMAGE"
                binding.headerStatus.text = "● NOT INDEXED"
                binding.headerStatus.setTextColor(0xFFFFC857.toInt())
                binding.sourceValue.text = "▌ SOURCE\n$uri\n\nNo persisted intelligence record was found for this URI."
                binding.technicalValue.text = "▌ INDEX STATE\nThe image can still be viewed, but OCR, object and face evidence are unavailable until it is indexed."
                binding.faceValue.text = "▌ FACE INTELLIGENCE\nNO PERSISTED FACE EVIDENCE"
                binding.objectValue.text = "▌ OBJECT INTELLIGENCE\nNO PERSISTED OBJECT EVIDENCE"
                binding.ocrValue.text = "▌ OCR EVIDENCE\nNO PERSISTED OCR EVIDENCE"
                binding.indexValue.text = "▌ INDEX\nSTATUS  NOT INDEXED\nText Encoder  DEFERRED\nVisual search remains an independent image-to-image capability."
                return@launch
            }

            val db = AppDatabase.getInstance(applicationContext)
            val faces = withContext(Dispatchers.IO) { db.faceDao().getByImageId(record.id) }
            val objects = withContext(Dispatchers.IO) { db.objectDao().findForImage(record.id) }
            val people = withContext(Dispatchers.IO) {
                faces.mapNotNull { it.personId }.distinct().let { ids ->
                    if (ids.isEmpty()) emptyList() else db.personDao().getByIds(ids)
                }
            }

            binding.headerTitle.text = record.fileName
            binding.headerStatus.text = "● INDEXED"
            binding.headerStatus.setTextColor(0xFF7DFF19.toInt())

            binding.sourceValue.text = buildString {
                append("▌ SOURCE / PROVENANCE\n")
                append("FILE  ${record.fileName}\n")
                append("PATH  ${record.filePath ?: "UNAVAILABLE"}\n")
                append("URI   ${record.uri}\n")
                append("TAKEN ${formatTime(record.dateTaken)}\n")
                append("MODIFIED ${formatTime(record.dateModified)}")
            }

            binding.technicalValue.text = buildString {
                append("▌ TECHNICAL PROFILE\n")
                append("DIMENSIONS  ${record.width} × ${record.height}\n")
                append("SIZE  ${formatBytes(record.fileSize)}\n")
                append("MIME  ${record.mimeType ?: "UNKNOWN"}\n")
                append("IMAGE ID  ${record.id}")
            }

            binding.faceValue.text = buildString {
                append("▌ FACE INTELLIGENCE\n")
                append("DETECTED  ${faces.size}\n")
                append("EMBEDDINGS  ${faces.count { it.hasEmbedding }}\n")
                append("MATCHABLE  ${faces.count { it.usableForMatching }}\n")
                append("QUALITY  ${faces.maxOfOrNull { it.qualityScore }?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"}\n")
                append("SUBJECT CLUSTERS  ")
                append(if (people.isEmpty()) "NONE" else people.joinToString { it.displayName?.takeIf(String::isNotBlank) ?: "SUBJECT ${it.id}" })
            }

            binding.objectValue.text = buildString {
                append("▌ OBJECT INTELLIGENCE\n")
                if (objects.isEmpty()) {
                    append("NO OBJECT OBSERVATIONS")
                } else {
                    append("OBSERVATIONS  ${objects.size}\n")
                    objects.take(20).forEachIndexed { index, item ->
                        if (index > 0) append("\n")
                        append("• ${item.label} / ${item.arabicLabel}  ${String.format(Locale.US, "%.1f%%", item.confidence * 100f)}")
                    }
                    if (objects.size > 20) append("\n… +${objects.size - 20} MORE")
                }
            }

            binding.ocrValue.text = buildString {
                append("▌ OCR EVIDENCE\n")
                append("LANGUAGE  ${record.ocrLanguage.ifBlank { "UNKNOWN" }}\n")
                append("QUALITY  ${String.format(Locale.US, "%.2f", record.ocrQualityScore)}\n")
                append("PASSES  ${record.ocrSuccessfulPasses}/${record.ocrPassCount}\n")
                append("ARABIC CHARACTERS  ${record.ocrArabicCharacters}\n")
                append("LATIN CHARACTERS  ${record.ocrLatinCharacters}\n\n")
                append(if (record.ocrText.isBlank()) "NO OCR TEXT DETECTED" else record.ocrText.take(3000))
            }

            binding.indexValue.text = buildString {
                append("▌ INDEX / MODEL STATE\n")
                append("INDEXED  ${formatTime(record.indexedAt)}\n")
                append("VISUAL SEARCH  IMAGE → IMAGE / AVAILABLE WHEN EMBEDDING EXISTS\n")
                append("TEXT ENCODER  DEFERRED / NOT USED IN CURRENT SEARCH\n")
                append("EVIDENCE SOURCE  LOCAL ROOM INDEX")
            }
        }
    }

    private fun formatTime(value: Long?): String {
        if (value == null || value <= 0L) return "UNKNOWN"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(value))
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    companion object {
        const val EXTRA_URI = "image_uri"

        fun start(context: Context, uri: String) {
            val intent = Intent(context, ImageViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }
}
