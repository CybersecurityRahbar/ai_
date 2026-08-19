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

/** Phase 7 command-center presentation over the Phase 6 operational health core. */
class DiagnosticsActivity : AppCompatActivity() {
    private val bg = Color.rgb(5, 11, 17)
    private val panel = Color.rgb(12, 24, 34)
    private val panel2 = Color.rgb(8, 17, 25)
    private val text = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val ok = Color.rgb(151, 255, 0)
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(bg)
        }
        root.addView(TextView(this).apply {
            text = "◈ INTELLIGENCE COMMAND / HEALTH CORE"
            textSize = 11f; setTextColor(cyan); setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "SYSTEM DIAGNOSTICS"
            textSize = 25f; setTextColor(text); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        })
        root.addView(TextView(this).apply {
            text = "LIVE TELEMETRY  •  MODEL READINESS  •  INDEX COVERAGE  •  FAILURE TRACE"
            textSize = 9f; setTextColor(muted); setPadding(0, 0, 0, dp(12))
        })

        statusView = TextView(this).apply {
            textSize = 11f; setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(neon); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("SELF TEST") { runSelfTest() }, weightParams(3, 0))
        actions.addView(button("REFRESH") { refresh() }, weightParams(3, 3))
        actions.addView(button("CLEAR LOG") { diagnostics.clear(); refresh() }, weightParams(3, 3))
        actions.addView(button("CLOSE") { finish() }, weightParams(3, 3))
        root.addView(actions)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        healthView = TextView(this).apply {
            textSize = 11f; setTextColor(text); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
            setPadding(dp(14), dp(14), dp(14), dp(14)); typeface = Typeface.MONOSPACE
        }
        content.addView(healthView, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, dp(10)) })
        logView = TextView(this).apply {
            textSize = 10f; setTextColor(text); setBackgroundColor(panel2); setPadding(dp(12), dp(12), dp(12), dp(20))
            typeface = Typeface.MONOSPACE; gravity = Gravity.START
        }
        content.addView(logView)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun weightParams(weight: Int, leftMargin: Int) = LinearLayout.LayoutParams(0, dp(48), weight.toFloat()).apply { setMargins(dp(leftMargin), 0, dp(3), 0) }

    private fun refresh() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { health.snapshot() }
            renderSnapshot(snapshot)
        }
    }

    private fun renderSnapshot(snapshot: IntelligenceHealthService.Snapshot) {
        val overallColor = when (snapshot.overall) { "READY" -> ok; "DEGRADED", "PARTIAL" -> warn; "CRITICAL" -> bad; else -> muted }
        statusView.setTextColor(overallColor)
        statusView.text = "SYSTEM ${snapshot.overall}   •   EVENTS ${snapshot.diagnosticsEvents}   •   ERRORS ${snapshot.errors}   •   WARNINGS ${snapshot.warnings}   •   JOURNAL ${diagnostics.sizeBytes()} B"
        val modelState = if (snapshot.modelInstalled) "READY (${snapshot.modelSizeBytes} B)" else "NOT IMPORTED"
        val sb = StringBuilder()
        sb.append("LIVE INTELLIGENCE TELEMETRY\n════════════════════════════════════════════════════════════\n")
        sb.append("IMAGES              ${snapshot.images}\nFACES               ${snapshot.faces}\nFACE EMBEDDINGS     ${snapshot.facesWithEmbedding} / ${snapshot.faces}  (${snapshot.faceCoverage}%)\nMATCHABLE FACES     ${snapshot.matchableFaces}\nPERSON CLUSTERS     ${snapshot.people}\nIMAGE EMBEDDINGS    ${snapshot.imageEmbeddings} / ${snapshot.images}  (${snapshot.visualCoverage}%)\nFACE EMBEDDINGS DB  ${snapshot.faceEmbeddings}\nTOTAL EMBEDDINGS    ${snapshot.totalEmbeddings}\nMOBILECLIP-S2       $modelState\nOCR ATTEMPTED       ${snapshot.ocrAttempted}\nOCR WITH EVIDENCE   ${snapshot.imagesWithOcr} / ${snapshot.images}  (${snapshot.ocrCoverage}%)\nOCR AVG QUALITY     ${"%.3f".format(java.util.Locale.US, snapshot.averageOcrQuality)}\nIMAGES WITH OBJECTS ${snapshot.imagesWithObjects} / ${snapshot.images}  (${snapshot.objectCoverage}%)\n\nPIPELINE STATUS\n────────────────────────────────────────────────────────────\n")
        snapshot.stages.forEach { stage -> sb.append(String.format(java.util.Locale.US, "%-18s %-10s %4d%%  %s\n", stage.name, stage.status.name, stage.coveragePercent, stage.detail)) }
        healthView.text = sb.toString()
        val events = diagnostics.readLatest(300)
        logView.text = if (events.isEmpty()) "DIAGNOSTIC JOURNAL\n────────────────────────────────────────────────────────────\nNO EVENTS YET\nابدأ فهرسة أو بحثًا لرؤية runId والمرحلة وسبب الفشل وStack Trace." else "DIAGNOSTIC JOURNAL / LATEST ${events.size}\n────────────────────────────────────────────────────────────\n" + events.asReversed().joinToString("\n")
    }

    private fun runSelfTest() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val lines = mutableListOf<String>()
                fun check(name: String, passed: Boolean, detail: String) { lines += "${if (passed) "[PASS]" else "[FAIL]"} $name — $detail"; diagnostics.record("SELF_TEST", name, if (passed) DiagnosticsManager.Severity.INFO else DiagnosticsManager.Severity.ERROR, detail) }
                val db = AppDatabase.getInstance(applicationContext)
                check("DATABASE", db.openHelper.writableDatabase.isOpen, "Room database is open")
                check("FACE_LANDMARKER", assetExists("face_landmarker.task"), "assets/face_landmarker.task")
                check("FACE_EMBEDDER", assetExists("models/face/mobilefacenet.tflite"), "assets/models/face/mobilefacenet.tflite")
                check("FACE_NET_512", assetExists("models/face/facenet_512.tflite") || assetExists("facenet_512.tflite"), "FaceNet-512 optional model")
                check("OBJECT_MODEL", assetExists("models/object/yolo26n_w8a32.tflite"), "assets/models/object/yolo26n_w8a32.tflite")
                check("ARABIC_OCR", assetExists("tessdata/ara.traineddata"), "assets/tessdata/ara.traineddata")
                val model = MobileClipModelManager(applicationContext)
                check("MOBILECLIP", model.isInstalled(), if (model.isInstalled()) "installed ${model.installedSizeBytes()} bytes" else "not imported")
                check("IMAGE_ROWS", db.imageDao().count() >= 0, "database query succeeded")
                check("FACE_ROWS", db.faceDao().count() >= 0, "database query succeeded")
                check("EMBEDDING_ROWS", db.embeddingDao().count() >= 0, "database query succeeded")
                lines
            }
            logView.text = "SELF TEST / COMPONENT VALIDATION\n────────────────────────────────────────────────────────────\n${result.joinToString("\n")}\n\n" + diagnostics.readLatest(120).asReversed().joinToString("\n")
            refresh()
        }
    }

    private fun assetExists(path: String): Boolean = try { assets.open(path).use { }; true } catch (_: Throwable) { false }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 9f; setTextColor(Color.rgb(5, 20, 10)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_button); setAllCaps(false); setOnClickListener { click() }
    }
}
