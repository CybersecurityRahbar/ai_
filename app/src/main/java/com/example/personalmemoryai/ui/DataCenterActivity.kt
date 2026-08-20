package com.example.personalmemoryai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.data.DataBackupManager
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.semantic.MobileClipModelManager
import com.example.personalmemoryai.vision.FaceNet512ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class DataCenterActivity : AppCompatActivity() {
    private val text = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val red = Color.rgb(255, 48, 79)
    private val amber = Color.rgb(255, 193, 72)

    private lateinit var backupManager: DataBackupManager
    private lateinit var modelManager: MobileClipModelManager
    private lateinit var faceNetManager: FaceNet512ModelManager
    private lateinit var status: TextView
    private lateinit var stats: TextView

    private val diagnostics by lazy { DiagnosticsManager.get(applicationContext) }

    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) exportBackup(uri) }

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importBackup(uri) }

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importModel(uri) }

    private val faceModelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFaceNetModel(uri) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backupManager = DataBackupManager(applicationContext)
        modelManager = MobileClipModelManager(applicationContext)
        faceNetManager = FaceNet512ModelManager(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(18))
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)

        root.addView(header())
        status = panel("SYSTEM READY", neon, 11f)
        root.addView(status, margin())
        root.addView(section("DATABASE TELEMETRY"), margin())
        stats = panel("LOADING…", text, 10f)
        root.addView(stats, margin())

        root.addView(section("BACKUP / RESTORE"), margin())
        root.addView(action("EXPORT KNOWLEDGE BASE", "Create a complete local .pmai backup") { exportPicker.launch("PersonalMemory_Backup_${System.currentTimeMillis()}.pmai") }, margin())
        root.addView(action("IMPORT KNOWLEDGE BASE", "Restore local evidence and indexes") { importPicker.launch(arrayOf("application/octet-stream", "*/*")) }, margin())

        root.addView(section("LOCAL MODEL CENTER"), margin())
        root.addView(action("IMPORT MOBILECLIP", "Install + validate local TFLite model") { modelPicker.launch(arrayOf("application/octet-stream", "application/tflite", "*/*")) }, margin())
        root.addView(action("REMOVE MOBILECLIP", "Delete local visual model") {
            modelManager.deleteModel(); updateStats(); status.text = "MOBILECLIP REMOVED"; status.setTextColor(red)
        }, margin())
        root.addView(action("IMPORT FACENET-512", "Install + validate 160×160 / 512-D model") { faceModelPicker.launch(arrayOf("application/octet-stream", "application/tflite", "*/*")) }, margin())
        root.addView(action("REMOVE FACENET-512", "Delete local identity model") {
            faceNetManager.deleteModel(); updateStats(); status.text = "FACENET-512 REMOVED"; status.setTextColor(red)
        }, margin())

        updateStats()
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
        elevation = dp(5).toFloat()
        addView(TextView(this@DataCenterActivity).apply { text = "◈ INTELLIGENCE COMMAND / DATA CENTER"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@DataCenterActivity).apply { text = "DATA & MODEL CENTER"; textSize = 27f; setTextColor(this@DataCenterActivity.text); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(2)) })
        addView(TextView(this@DataCenterActivity).apply { text = "EVIDENCE • INDEX • BACKUP • MODEL INTEGRITY"; textSize = 9f; setTextColor(muted) })
    }

    private fun section(value: String) = TextView(this).apply { text = "▌  $value"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) }

    private fun panel(value: String, color: Int, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setTypeface(Typeface.MONOSPACE, Typeface.NORMAL); setPadding(dp(14), dp(13), dp(14), dp(13)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); setTextIsSelectable(true)
    }

    private fun action(title: String, subtitle: String, click: () -> Unit) = Button(this).apply {
        text = "▸  $title\n    $subtitle"; textSize = 9f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(this@DataCenterActivity.text); setAllCaps(false); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_action); setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, dp(68))
    }

    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(9)) }

    private fun updateStats() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(applicationContext)
                Stats(db.imageDao().count(), db.faceDao().count(), db.personDao().count(), db.embeddingDao().count())
            }
            val databaseSize = formatBytes(getDatabasePath("personal_memory.db").length())
            val mobileClipState = if (modelManager.isInstalled()) {
                "IMPORTED • ${formatBytes(modelManager.installedSizeBytes())} • HASH ${modelManager.installedModelVersion().takeLast(16)} • runtime validated when loaded"
            } else "NOT INSTALLED"
            val faceNetState = if (faceNetManager.isInstalled()) {
                "IMPORTED • ${formatBytes(faceNetManager.installedSizeBytes())} • 160×160 • 512-D • validated at import"
            } else "NOT INSTALLED"
            stats.text = """
                IMAGES       ${snapshot.images}
                FACES        ${snapshot.faces}
                PERSONS      ${snapshot.persons}
                EMBEDDINGS   ${snapshot.embeddings}
                DATABASE     $databaseSize

                MOBILECLIP   $mobileClipState
                FACENET-512  $faceNetState

                MODEL STATUS NOTE
                File presence is not reported as runtime READY.
                Runtime readiness requires successful tensor loading/inference.
            """.trimIndent()
            stats.setTextColor(text)
            status.text = "● DATA CENTER READY • LOCAL-ONLY STORAGE"
            status.setTextColor(neon)
        }
    }

    private fun setBusy(busy: Boolean, message: String) { status.text = message; status.setTextColor(if (busy) cyan else neon) }

    private fun exportBackup(uri: android.net.Uri) {
        lifecycleScope.launch {
            setBusy(true, "◉ EXPORTING KNOWLEDGE BASE…")
            val run = diagnostics.begin("BACKUP_EXPORT", mapOf("destination" to uri.toString()))
            try {
                val result = backupManager.exportBackup(uri) { progress -> runOnUiThread { status.text = "◉ EXPORT $progress%" } }
                updateStats()
                if (result.missingImageIds.isEmpty()) run.success("Backup export completed") else run.warning("Backup exported with missing images", mapOf("missing" to result.missingImageIds.size.toString()))
                status.text = "● EXPORT COMPLETE • ${result.copiedImages}/${result.imageCount} IMAGES"
                status.setTextColor(if (result.missingImageIds.isEmpty()) neon else amber)
            } catch (error: Exception) { run.failure("EXPORT", error); status.text = "● EXPORT ERROR • ${error.message}"; status.setTextColor(red) }
        }
    }

    private fun importBackup(uri: android.net.Uri) {
        lifecycleScope.launch {
            setBusy(true, "◉ RESTORING KNOWLEDGE BASE…")
            val run = diagnostics.begin("BACKUP_IMPORT", mapOf("source" to uri.toString()))
            try {
                val result = backupManager.importBackup(uri) { progress -> runOnUiThread { status.text = "◉ RESTORE $progress%" } }
                updateStats()
                if (result.restoredImages == result.imageCount) run.success("Backup import completed") else run.warning("Backup imported with missing images")
                status.text = "● RESTORE COMPLETE • ${result.restoredImages}/${result.imageCount} IMAGES"
                status.setTextColor(if (result.restoredImages == result.imageCount) neon else amber)
            } catch (error: Exception) { run.failure("IMPORT", error); status.text = "● RESTORE ERROR • ${error.message}"; status.setTextColor(red) }
        }
    }

    private fun importModel(uri: android.net.Uri) {
        lifecycleScope.launch {
            setBusy(true, "◉ IMPORTING MOBILECLIP…")
            val run = diagnostics.begin("MOBILECLIP_IMPORT_UI", mapOf("source" to uri.toString()))
            try {
                withContext(Dispatchers.IO) { com.example.personalmemoryai.semantic.MobileClipModelManager(applicationContext).importModel(uri) { copied, total -> runOnUiThread { status.text = "◉ MOBILECLIP ${if (total > 0) ((copied * 100L) / total).toInt() else 0}%" } } }
                updateStats(); run.success("MobileCLIP imported and runtime-validated"); status.text = "● MOBILECLIP IMPORTED • TENSOR + INFERENCE VALIDATED"; status.setTextColor(neon)
            } catch (error: Exception) { run.failure("IMPORT", error); status.text = "● MOBILECLIP ERROR • ${error.message}"; status.setTextColor(red) }
        }
    }

    private fun importFaceNetModel(uri: android.net.Uri) {
        lifecycleScope.launch {
            setBusy(true, "◉ IMPORTING FACENET-512…")
            val run = diagnostics.begin("FACENET512_IMPORT_UI", mapOf("source" to uri.toString()))
            try {
                withContext(Dispatchers.IO) { faceNetManager.importModel(uri) { copied, total -> runOnUiThread { status.text = "◉ FACENET-512 ${if (total > 0) ((copied * 100L) / total).toInt() else 0}%" } } }
                updateStats(); run.success("FaceNet-512 imported and inference-validated"); status.text = "● FACENET-512 IMPORTED • TENSOR + INFERENCE VALIDATED"; status.setTextColor(neon)
            } catch (error: Exception) { run.failure("IMPORT", error); status.text = "● FACENET-512 ERROR • ${error.message}"; status.setTextColor(red) }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB"); var value = bytes.toDouble(); var index = 0
        while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
        return "${DecimalFormat("0.0").format(value)} ${units[index]}"
    }

    private data class Stats(val images: Int, val faces: Long, val persons: Long, val embeddings: Long)
}
