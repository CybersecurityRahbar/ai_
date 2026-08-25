package com.example.personalmemoryai

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.diagnostics.IntelligenceHealthService
import com.example.personalmemoryai.ui.DataCenterActivity
import com.example.personalmemoryai.ui.DiagnosticsActivity
import com.example.personalmemoryai.ui.EvidenceRelationshipsActivity
import com.example.personalmemoryai.ui.EvidenceSearchActivity
import com.example.personalmemoryai.ui.FaceSearchActivity
import com.example.personalmemoryai.ui.ImageIntelligenceActivity
import com.example.personalmemoryai.ui.InvestigationActivity
import com.example.personalmemoryai.ui.ObjectIntelligenceActivity
import com.example.personalmemoryai.ui.OcrEvidenceActivity
import com.example.personalmemoryai.ui.PeopleIntelligenceActivity
import com.example.personalmemoryai.ui.ReverseImageSearchActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IntelligenceHomeActivity : AppCompatActivity() {
    private val textColor = Color.rgb(233, 255, 244)
    private val muted = Color.rgb(127, 169, 154)
    private val green = Color.rgb(57, 255, 136)
    private val red = Color.rgb(255, 48, 79)
    private val cyan = Color.rgb(53, 232, 255)
    private val amber = Color.rgb(255, 210, 63)
    private val violet = Color.rgb(179, 107, 255)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(3, 7, 9)
        window.navigationBarColor = Color.rgb(3, 7, 9)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundResource(R.drawable.bg_intelligence) }
        root.addView(header())
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(28)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)

        val overview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title("LIVE INTELLIGENCE OVERVIEW", green)); content.addView(overview)
        val images = metricCard("IMAGES", "—", cyan); val faces = metricCard("FACES", "—", green); val people = metricCard("SUBJECTS", "—", violet); val objects = metricCard("OBJECTS", "—", amber)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; listOf(images, faces).forEach { row1.addView(it, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; listOf(people, objects).forEach { row2.addView(it, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
        overview.addView(row1); overview.addView(row2)
        val health = healthPanel(); content.addView(health, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, dp(8)) })
        val engineMatrix = engineMatrix(); content.addView(title("LIVE ENGINE MATRIX", cyan)); content.addView(engineMatrix, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })

        content.addView(title("DATA INGESTION / INDEXING", green))
        content.addView(action("IMAGE INGESTION / INDEXING CONSOLE", "Select local images • OCR • objects • metadata • Face/Visual index controls", green) {
            startActivity(Intent(this, MainActivity::class.java))
        })

        content.addView(title("SEARCH / EVIDENCE RETRIEVAL", cyan))
        content.addView(action("LOCAL REVERSE IMAGE SEARCH", "DigiKam Haar • independent corpus • query-image retrieval • crop tolerant", cyan) {
            startActivity(Intent(this, ReverseImageSearchActivity::class.java))
        })
        content.addView(action("EVIDENCE SEARCH CONSOLE", "Image-to-image similarity • OCR • object retrieval • no Text Encoder dependency", cyan) { startActivity(Intent(this, EvidenceSearchActivity::class.java)) })
        content.addView(action("COMPOSITE INVESTIGATION", "Face • body • pose • appearance • scene • visual • OCR • objects", violet) { startActivity(Intent(this, InvestigationActivity::class.java)) })
        content.addView(action("EVIDENCE RELATIONSHIPS", "Subject graph • shared images • linked subjects • object evidence", green) {
            val intent = Intent(this, EvidenceRelationshipsActivity::class.java)
            intent.putExtra("person_id", 0L)
            startActivity(intent)
        })
        content.addView(title("IDENTITY / HUMAN ANALYSIS", green))
        content.addView(action("FACE MATCH CONSOLE", "MobileFaceNet • FaceNet-512 • 478-point shape • ranked confidence", green) { startActivity(Intent(this, FaceSearchActivity::class.java)) })
        content.addView(action("PEOPLE & FACE INTELLIGENCE", "Clusters • observations • representatives • identity evidence", green) { startActivity(Intent(this, PeopleIntelligenceActivity::class.java)) })
        content.addView(title("VISUAL / EVIDENCE ANALYSIS", cyan))
        content.addView(action("IMAGE INTELLIGENCE", "Similarity • metadata • visual evidence • indexed images", cyan) { startActivity(Intent(this, ImageIntelligenceActivity::class.java)) })
        content.addView(action("OBJECT INTELLIGENCE", "Detection • confidence • bounding boxes • model telemetry", amber) { startActivity(Intent(this, ObjectIntelligenceActivity::class.java)) })
        content.addView(title("TEXT / OCR INTELLIGENCE", violet))
        content.addView(action("OCR EVIDENCE CONSOLE", "Arabic OCR • quality • fuzzy retrieval • extraction telemetry", violet) { startActivity(Intent(this, OcrEvidenceActivity::class.java)) })
        content.addView(title("OPERATIONS / MODEL CONTROL", cyan))
        content.addView(action("DATA CENTER", "Backup • restore • database statistics • model management", cyan) { startActivity(Intent(this, DataCenterActivity::class.java)) })
        content.addView(action("SYSTEM DIAGNOSTICS", "Errors • warnings • stages • stack traces • execution journal", red) { startActivity(Intent(this, DiagnosticsActivity::class.java)) })

        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { IntelligenceHealthService(applicationContext).snapshot() }
            images.findMetricValue()?.text = snapshot.images.toString(); faces.findMetricValue()?.text = snapshot.faces.toString(); people.findMetricValue()?.text = snapshot.people.toString(); objects.findMetricValue()?.text = snapshot.imagesWithObjects.toString()
            val overallColor = when (snapshot.overall) { "READY" -> green; "DEGRADED", "PARTIAL" -> amber; "CRITICAL" -> red; else -> muted }
            health.text = buildHealthText(snapshot); health.setTextColor(overallColor); pulse(health); updateEngineMatrix(engineMatrix, snapshot)
        }
    }

    private fun buildHealthText(snapshot: IntelligenceHealthService.Snapshot): String {
        val runtime = snapshot.engines.joinToString("\n") { engine -> val latency = if (engine.lastLatencyMs > 0) " / ${engine.lastLatencyMs}ms" else ""; "${engine.name.padEnd(19)} ${engine.status.name.padEnd(10)} ${engine.lastEvent}$latency" }
        return "● SYSTEM HEALTH  /  LOCAL CORE  /  ${snapshot.overall}\n\n" +
            "FACE ENGINE       ${snapshot.facesWithEmbedding}/${snapshot.faces} EMBEDDINGS  /  ${snapshot.faceCoverage}%\n" +
            "MATCHABLE FACES   ${snapshot.matchableFaces}\n" + "PERSON CLUSTERS   ${snapshot.people}\n" +
            "OBJECT ENGINE     ${snapshot.imagesWithObjects}/${snapshot.images} IMAGES  /  ${snapshot.objectCoverage}%\n" +
            "MOBILECLIP-S2     ${if (snapshot.modelInstalled) "IMPORTED / VALIDATION SEPARATE" else "NOT IMPORTED"}\n" +
            "VISUAL INDEX      ${snapshot.imageEmbeddings}/${snapshot.images}  /  ${snapshot.visualCoverage}%\n" +
            "OCR COVERAGE      ${snapshot.imagesWithOcr}/${snapshot.images}  /  ${snapshot.ocrCoverage}%\n" +
            "OCR QUALITY       ${"%.3f".format(Locale.US, snapshot.averageOcrQuality)}\n" + "EMBEDDINGS TOTAL  ${snapshot.totalEmbeddings}\n" +
            "TRACE EVENTS      ${snapshot.diagnosticsEvents}\n" + "ERRORS            ${snapshot.errors}\n" + "WARNINGS          ${snapshot.warnings}\n\n" +
            "RUNTIME TELEMETRY\n" + runtime + "\n\nENGINE STATES ARE DERIVED FROM PERSISTED HEALTH DATA."
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); setBackgroundResource(R.drawable.panel_intelligence); elevation = dp(5).toFloat()
        addView(TextView(this@IntelligenceHomeActivity).apply { text = "PMAI // LOCAL INTELLIGENCE CORE"; textSize = 10f; setTextColor(green); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@IntelligenceHomeActivity).apply { text = "INTELLIGENCE COMMAND CENTER"; textSize = 25f; setTextColor(textColor); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(3)) })
        addView(TextView(this@IntelligenceHomeActivity).apply { text = "IDENTITY  •  VISUAL  •  OCR  •  OBJECTS  •  EVIDENCE  •  TRACE"; textSize = 9f; setTextColor(muted) })
        addView(TextView(this@IntelligenceHomeActivity).apply { text = "●  OFFLINE / LOCAL / MONITORED"; textSize = 10f; setTextColor(green); setTypeface(null, Typeface.BOLD); setPadding(0, dp(11), 0, 0) })
        addView(TextView(this@IntelligenceHomeActivity).apply { text = SimpleDateFormat("yyyy-MM-dd  /  HH:mm:ss", Locale.US).format(Date()); textSize = 8f; setTextColor(muted); setPadding(0, dp(4), 0, 0) })
    }
    private fun healthPanel() = TextView(this).apply { text = "● SYSTEM HEALTH  /  LOCAL CORE  /  LOADING…"; textSize = 11f; setTextColor(textColor); setTypeface(null, Typeface.BOLD); setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundResource(R.drawable.panel_intelligence); elevation = dp(3).toFloat() }
    private fun engineMatrix() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundResource(R.drawable.panel_intelligence); elevation = dp(3).toFloat(); addView(engineRow("FACE IDENTITY", "CHECKING…", green)); addView(engineRow("FACIAL SHAPE", "CHECKING…", green)); addView(engineRow("MOBILECLIP-S2", "CHECKING…", cyan)); addView(engineRow("OBJECT ANALYSIS", "CHECKING…", amber)); addView(engineRow("ARABIC OCR", "CHECKING…", violet)); addView(engineRow("DIAGNOSTICS", "CHECKING…", red)) }
    private fun engineRow(name: String, state: String, accent: Int) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(5), dp(7), dp(5), dp(7)); addView(TextView(this@IntelligenceHomeActivity).apply { text = "◆  $name"; textSize = 9f; setTextColor(textColor); setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f)); addView(TextView(this@IntelligenceHomeActivity).apply { tag = "engine_state"; text = state; textSize = 8f; setTextColor(accent); setTypeface(null, Typeface.BOLD); gravity = Gravity.END }) }
    private fun updateEngineMatrix(matrix: LinearLayout, snapshot: IntelligenceHealthService.Snapshot) {
        val byName = snapshot.engines.associateBy { it.name.lowercase(Locale.US) }; val mobileClip = byName["mobileclip-s2"]; val mobileFace = byName["mobilefacenet"]; val faceNet = byName["facenet-512"]; val shape = byName["facial landmarks"]; val objects = byName["object detection"]; val ocr = byName["arabic ocr"]
        val states = listOf(mobileFace?.let { "${it.status.name} / ${snapshot.faceCoverage}%" } ?: "${snapshot.faceCoverage}% COVERAGE", shape?.status?.name ?: if (snapshot.faceCoverage > 0) "PERSISTED" else "NOT_READY", mobileClip?.let { "${it.status.name} / ${snapshot.visualCoverage}%" } ?: if (snapshot.modelInstalled) "IMPORTED / NO TRACE" else "NOT_IMPORTED", objects?.status?.name ?: "${snapshot.objectCoverage}% COVERAGE", ocr?.let { "${it.status.name} / ${snapshot.ocrCoverage}%" } ?: "${snapshot.ocrCoverage}% COVERAGE", when { snapshot.errors > 0 -> "CRITICAL / ${snapshot.errors}"; snapshot.warnings > 0 -> "DEGRADED / ${snapshot.warnings}"; else -> "NOMINAL" })
        matrix.childrenSequence().toList().forEachIndexed { index, row -> row.findViewWithTag<TextView>("engine_state")?.text = states[index] }; faceNet?.lastEvent
    }
    private fun title(text: String, color: Int) = TextView(this).apply { this.text = "▌  $text"; textSize = 10f; setTextColor(color); setTypeface(null, Typeface.BOLD); setPadding(dp(3), dp(13), dp(3), dp(6)) }
    private fun metricCard(label: String, value: String, accent: Int) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(8)); setBackgroundResource(R.drawable.panel_intelligence); elevation = dp(2).toFloat(); addView(TextView(this@IntelligenceHomeActivity).apply { text = label; textSize = 8f; setTextColor(muted) }); addView(TextView(this@IntelligenceHomeActivity).apply { tag = "metric_value"; text = value; textSize = 23f; setTextColor(accent); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, 0) }); addView(TextView(this@IntelligenceHomeActivity).apply { text = "LIVE DATA"; textSize = 7f; setTextColor(muted); setPadding(0, dp(2), 0, 0) }) }
    private fun View.findMetricValue(): TextView? = findViewWithTag("metric_value")
    private fun action(title: String, subtitle: String, accent: Int, click: () -> Unit) = Button(this).apply { text = "●  $title\n    $subtitle"; textSize = 10f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(accent); setBackgroundResource(R.drawable.bg_neon_action); setAllCaps(false); setPadding(dp(15), dp(7), dp(15), dp(7)); setOnClickListener { click() }; stateListAnimator = null; elevation = dp(3).toFloat(); layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { setMargins(0, dp(3), 0, dp(3)) }; setTypeface(Typeface.DEFAULT, Typeface.BOLD) }
    private fun pulse(view: View) { ValueAnimator.ofFloat(0.82f, 1f, 0.82f).apply { duration = 1700L; repeatCount = ValueAnimator.INFINITE; addUpdateListener { value -> view.alpha = value.animatedValue as Float }; start() } }
}
