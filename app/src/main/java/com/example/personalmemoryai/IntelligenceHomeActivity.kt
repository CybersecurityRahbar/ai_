package com.example.personalmemoryai

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.semantic.MobileClipModelManager
import com.example.personalmemoryai.ui.DataCenterActivity
import com.example.personalmemoryai.ui.DiagnosticsActivity
import com.example.personalmemoryai.ui.FaceSearchActivity
import com.example.personalmemoryai.ui.ImageIntelligenceActivity
import com.example.personalmemoryai.ui.ObjectIntelligenceActivity
import com.example.personalmemoryai.ui.OcrEvidenceActivity
import com.example.personalmemoryai.ui.PeopleIntelligenceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IntelligenceHomeActivity : AppCompatActivity() {
    private val bg = Color.rgb(8, 15, 23); private val panel = Color.rgb(15, 27, 39); private val textColor = Color.rgb(235, 246, 255); private val muted = Color.rgb(103, 139, 164); private val accent = Color.rgb(31, 91, 132)
    private fun dp(value: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(bg) }
        root.addView(header())
        val scroll = android.widget.ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(24)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)

        val statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val images = metric("IMAGES", "—"); val faces = metric("FACES", "—"); val people = metric("SUBJECTS", "—"); val objects = metric("OBJECTS", "—")
        listOf(images, faces, people, objects).forEachIndexed { i, view -> statsRow.addView(view, LinearLayout.LayoutParams(0, dp(96), 1f).apply { setMargins(if (i == 0) 0 else dp(3), 0, if (i == 3) 0 else dp(3), 0) }) }
        content.addView(statsRow)

        val status = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(173, 201, 219)); setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(panel) }
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, dp(10)) })
        content.addView(section("IDENTITY / FACE INTELLIGENCE"))
        content.addView(action("SEARCH FACE / IDENTITY CANDIDATES", "MobileFaceNet + FaceNet-512 + landmarks + confidence-ranked retrieval") { startActivity(Intent(this, FaceSearchActivity::class.java)) })
        content.addView(action("OPEN PEOPLE & FACE INTELLIGENCE", "Face clusters, observations and representative embeddings") { startActivity(Intent(this, PeopleIntelligenceActivity::class.java)) })
        content.addView(section("OCR / TEXT INTELLIGENCE"))
        content.addView(action("OPEN OCR EVIDENCE CONSOLE", "Arabic-aware search, fuzzy retrieval, OCR quality, pass telemetry and evidence") { startActivity(Intent(this, OcrEvidenceActivity::class.java)) })
        content.addView(section("OBJECT / VISUAL EVIDENCE"))
        content.addView(action("OPEN OBJECT INTELLIGENCE CONSOLE", "YOLO detection, confidence, bounding boxes, evidence search and model diagnostics") { startActivity(Intent(this, ObjectIntelligenceActivity::class.java)) })
        content.addView(action("OPEN INDEXED IMAGE INTELLIGENCE", "OCR, objects, metadata and indexed evidence") { startActivity(Intent(this, ImageIntelligenceActivity::class.java)) })
        content.addView(section("DATA CENTER / MODELS"))
        content.addView(action("DATA CENTER", "Backup, restore, database statistics and model management") { startActivity(Intent(this, DataCenterActivity::class.java)) })
        content.addView(section("SYSTEM HEALTH / TRACEABILITY"))
        content.addView(action("OPEN SYSTEM DIAGNOSTICS", "Live event journal, failures, stack traces and pipeline stages") { startActivity(Intent(this, DiagnosticsActivity::class.java)) })

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext); val diagnostics = DiagnosticsManager.get(applicationContext)
            val values = withContext(Dispatchers.IO) { arrayOf(db.imageDao().count(), db.faceDao().count(), db.personDao().count(), db.embeddingDao().count(), db.objectDao().count()) }
            val events = withContext(Dispatchers.IO) { diagnostics.readLatest(500) }
            val errors = events.count { it.contains("\"severity\":\"ERROR\"") || it.contains("\"severity\":\"CRITICAL\"") }; val warnings = events.count { it.contains("\"severity\":\"WARNING\"") }
            (images.getChildAt(1) as TextView).text = values[0].toString(); (faces.getChildAt(1) as TextView).text = values[1].toString(); (people.getChildAt(1) as TextView).text = values[2].toString(); (objects.getChildAt(1) as TextView).text = values[4].toString()
            val model = MobileClipModelManager(applicationContext)
            status.text = "SYSTEM HEALTH\nLOCAL INTELLIGENCE: ONLINE\nFACE OBSERVATIONS: ${values[1]}\nPERSON CLUSTERS: ${values[2]}\nOBJECT OBSERVATIONS: ${values[4]}\nTOTAL EMBEDDINGS: ${values[3]}\nMOBILECLIP-S2: ${if (model.isInstalled()) "READY" else "NOT IMPORTED"}\nDIAGNOSTICS: ${events.size} events • $errors errors • $warnings warnings"
        }
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(Color.rgb(16, 29, 42))
        addView(TextView(this@IntelligenceHomeActivity).apply { text = "FACE INTELLIGENCE SYSTEM"; textSize = 11f; setTextColor(Color.rgb(143, 211, 255)); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@IntelligenceHomeActivity).apply { text = "نظام الاستخبارات المحلي للتعرف على الوجوه"; textSize = 24f; setTextColor(textColor); setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, dp(3)) })
        addView(TextView(this@IntelligenceHomeActivity).apply { text = "IDENTITY ANALYSIS • VISUAL EVIDENCE • OBJECT INTELLIGENCE • SYSTEM TRACE"; textSize = 9f; setTextColor(muted) })
        addView(TextView(this@IntelligenceHomeActivity).apply { text = "● OFFLINE / LOCAL CORE / MONITORED"; textSize = 10f; setTextColor(Color.rgb(73, 210, 166)); setTypeface(null, Typeface.BOLD); setPadding(0, dp(12), 0, 0) })
    }
    private fun section(title: String) = TextView(this).apply { text = title; textSize = 10f; setTextColor(Color.rgb(131, 188, 224)); setTypeface(null, Typeface.BOLD); setPadding(dp(2), dp(14), dp(2), dp(7)) }
    private fun metric(label: String, value: String) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(10), dp(9), dp(10)); setBackgroundColor(panel); addView(TextView(this@IntelligenceHomeActivity).apply { text = label; textSize = 8f; setTextColor(muted); maxLines = 1 }); addView(TextView(this@IntelligenceHomeActivity).apply { text = value; textSize = 20f; setTextColor(textColor); setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, 0); maxLines = 1 }) }
    private fun action(title: String, subtitle: String, click: () -> Unit) = Button(this).apply { text = "$title\n$subtitle"; textSize = 11f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(textColor); setBackgroundColor(accent); setAllCaps(false); setPadding(dp(16), dp(10), dp(16), dp(10)); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, dp(64)).apply { setMargins(0, 0, 0, dp(7)) } }
}
