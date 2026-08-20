package com.example.personalmemoryai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.diagnostics.IntelligenceHealthService
import com.example.personalmemoryai.semantic.MobileClipModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiagnosticsActivity : AppCompatActivity() {
    private val primaryText = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val warn = Color.rgb(255, 193, 72)
    private val bad = Color.rgb(255, 48, 79)
    private lateinit var statusView: TextView
    private lateinit var healthView: TextView
    private lateinit var logView: TextView
    private val diagnostics by lazy { DiagnosticsManager.get(applicationContext) }
    private val health by lazy { IntelligenceHealthService(applicationContext) }
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(16)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        root.addView(header())
        statusView = panelText("BOOT / READING HEALTH CORE", neon, 11f)
        root.addView(statusView, margin())
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        action(actions, "SELF TEST", 1f) { runSelfTest() }
        action(actions, "REFRESH", 1f) { refresh() }
        action(actions, "CLEAR", 1f) { diagnostics.clear(); refresh() }
        root.addView(actions, margin())
        healthView = panelText("LOADING…", primaryText, 10f)
        root.addView(section("PIPELINE TELEMETRY", cyan), margin())
        root.addView(healthView, margin())
        logView = panelText("NO EVENTS YET", primaryText, 9f)
        root.addView(section("DIAGNOSTIC JOURNAL", cyan), margin())
        root.addView(logView)
        refresh()
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
        elevation = dp(5).toFloat()
        addView(TextView(this@DiagnosticsActivity).apply { text = "◈ INTELLIGENCE COMMAND / HEALTH CORE"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@DiagnosticsActivity).apply { text = "SYSTEM DIAGNOSTICS"; textSize = 27f; setTextColor(primaryText); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(2)) })
        addView(TextView(this@DiagnosticsActivity).apply { text = "LIVE TELEMETRY • MODEL READINESS • INDEX COVERAGE • FAILURE TRACE"; textSize = 9f; setTextColor(muted) })
    }

    private fun section(v: String, c: Int) = TextView(this).apply { text = "▌  $v"; textSize = 10f; setTextColor(c); setTypeface(null, Typeface.BOLD) }
    private fun panelText(v: String, c: Int, size: Float) = TextView(this).apply { text = v; textSize = size; setTextColor(c); setTypeface(Typeface.MONOSPACE, Typeface.NORMAL); setPadding(dp(14), dp(13), dp(14), dp(13)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); setTextIsSelectable(true) }
    private fun action(parent: LinearLayout, label: String, weight: Float, click: () -> Unit) {
        parent.addView(Button(this).apply { text = label; textSize = 8.5f; setAllCaps(false); setTextColor(primaryText); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_action); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(2), 0, dp(2), 0) } })
    }
    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(9)) }

    private fun refresh() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { health.snapshot() }
            val color = when (snapshot.overall) { "READY" -> neon; "DEGRADED", "PARTIAL" -> warn; "CRITICAL" -> bad; else -> muted }
            statusView.setTextColor(color)
            statusView.text = "SYSTEM ${snapshot.overall}  •  EVENTS ${snapshot.diagnosticsEvents}  •  ERRORS ${snapshot.errors}  •  WARNINGS ${snapshot.warnings}  •  JOURNAL ${diagnostics.sizeBytes()} B"
            val sb = StringBuilder("IMAGES ${snapshot.images}   •   FACES ${snapshot.faces}   •   PEOPLE ${snapshot.people}\nFACE EMBEDDINGS ${snapshot.facesWithEmbedding}/${snapshot.faces} (${snapshot.faceCoverage}%)   •   MATCHABLE ${snapshot.matchableFaces}\nVISUAL EMBEDDINGS ${snapshot.imageEmbeddings}/${snapshot.images} (${snapshot.visualCoverage}%)\nOCR ${snapshot.imagesWithOcr}/${snapshot.images} (${snapshot.ocrCoverage}%)   •   OBJECTS ${snapshot.imagesWithObjects}/${snapshot.images} (${snapshot.objectCoverage}%)\n\nMODEL: ${if (snapshot.modelInstalled) "MOBILECLIP READY / ${snapshot.modelSizeBytes} B" else "MOBILECLIP NOT IMPORTED"}\n\n")
            snapshot.stages.forEach { stage -> sb.append("${stage.name.padEnd(18)} ${stage.status.name.padEnd(10)} ${stage.coveragePercent}%  ${stage.detail}\n") }
            healthView.text = sb.toString()
            val events = diagnostics.readLatest(250)
            logView.text = if (events.isEmpty()) "NO DIAGNOSTIC EVENTS\n\nRun indexing, OCR, object detection or a model import to populate the journal." else events.asReversed().joinToString("\n")
        }
    }

    private fun runSelfTest() {
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(applicationContext)
                val out = mutableListOf<String>()
                fun check(name: String, ok: Boolean, detail: String) { out += "${if (ok) "[PASS]" else "[FAIL]"} $name  $detail"; diagnostics.record("SELF_TEST", name, if (ok) DiagnosticsManager.Severity.INFO else DiagnosticsManager.Severity.ERROR, detail) }
                check("DATABASE", db.openHelper.writableDatabase.isOpen, "Room database")
                check("FACE_LANDMARKER", assetExists("face_landmarker.task"), "face_landmarker.task")
                check("MOBILEFACENET", assetExists("models/face/mobilefacenet.tflite"), "MobileFaceNet")
                check("FACENET_512", assetExists("models/face/facenet_512.tflite") || assetExists("facenet_512.tflite"), "FaceNet-512 optional/importable")
                check("YOLO", assetExists("models/object/yolo26n_w8a32.tflite"), "YOLO object model")
                check("ARABIC_OCR", assetExists("tessdata/ara.traineddata"), "Arabic traineddata")
                val clip = MobileClipModelManager(applicationContext)
                check("MOBILECLIP", clip.isInstalled(), if (clip.isInstalled()) "installed ${clip.installedSizeBytes()} B" else "not imported")
                check("IMAGE_TABLE", db.imageDao().count() >= 0, "query OK")
                check("FACE_TABLE", db.faceDao().count() >= 0, "query OK")
                check("EMBEDDING_TABLE", db.embeddingDao().count() >= 0, "query OK")
                out
            }
            logView.text = "SELF TEST\n════════════════════════════════════════════════\n${lines.joinToString("\n")}"
            refresh()
        }
    }

    private fun assetExists(path: String): Boolean = try { assets.open(path).use { }; true } catch (_: Throwable) { false }
}
