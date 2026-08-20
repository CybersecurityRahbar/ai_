package com.example.personalmemoryai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.indexing.YoloObjectDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ObjectIntelligenceActivity : AppCompatActivity() {
    private val primaryText = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val red = Color.rgb(255, 48, 79)
    private lateinit var health: TextView
    private lateinit var results: TextView
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            health.text = "◉ RUNNING / OBJECT INFERENCE\nVALIDATING MODEL + INPUT…"; health.setTextColor(cyan)
            try {
                val output = withContext(Dispatchers.Default) {
                    val detector = YoloObjectDetector(applicationContext)
                    try { val started = System.nanoTime(); val detections = detector.detect(uri); detections to ((System.nanoTime() - started) / 1_000_000L) } finally { detector.close() }
                }
                val detections = output.first
                health.text = "● OBJECT ENGINE: ONLINE\nINFERENCE: SUCCESS\nDETECTIONS: ${detections.size}\nLATENCY: ${output.second} ms\nMODEL: YOLO26n W8A32"; health.setTextColor(neon)
                results.text = if (detections.isEmpty()) "ZERO RESULT / VALID\n\nInference completed successfully, but no object crossed the configured confidence threshold." else detections.mapIndexed { i, d -> "${i + 1}. ${d.label.uppercase()} / ${d.arabicLabel}\n   CONFIDENCE ${(d.confidence * 100f).roundToInt()}%\n   BOX ${d.left.roundToInt()},${d.top.roundToInt()} → ${d.right.roundToInt()},${d.bottom.roundToInt()}" }.joinToString("\n\n")
            } catch (t: Throwable) {
                health.text = "● OBJECT ENGINE: ERROR\n${t.javaClass.simpleName}: ${t.message}"; health.setTextColor(red); DiagnosticsManager.get(applicationContext).begin("OBJECT_CONSOLE", mapOf("uri" to uri.toString())).failure("INFERENCE", t)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(18)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }; setContentView(scroll)
        root.addView(header())
        health = TextView(this).apply { textSize = 11f; setTextColor(neon); setTypeface(null, Typeface.BOLD); setPadding(dp(14), dp(13), dp(14), dp(13)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel) }
        root.addView(health, margin()); root.addView(action("RUN OBJECT MODEL TEST", "Select an image and execute the real YOLO pipeline") { picker.launch("image/*") }, margin()); root.addView(action("SEARCH OBJECT EVIDENCE", "Search persisted object observations and source images") { showObjectSearch() }, margin()); root.addView(section("PERSISTED OBJECT EVIDENCE"), margin())
        results = TextView(this).apply { textSize = 11f; setTextColor(primaryText); setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); setTextIsSelectable(true) }
        root.addView(results); refresh()
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(5).toFloat()
        addView(TextView(this@ObjectIntelligenceActivity).apply { text = "◈ VISUAL INTELLIGENCE / OBJECTS"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@ObjectIntelligenceActivity).apply { text = "OBJECT INTELLIGENCE"; textSize = 27f; setTextColor(primaryText); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(2)) })
        addView(TextView(this@ObjectIntelligenceActivity).apply { text = "DETECTION • CONFIDENCE • BOXES • PERSISTED EVIDENCE"; textSize = 9f; setTextColor(muted) })
    }
    private fun section(value: String) = TextView(this).apply { text = "▌  $value"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD); setPadding(dp(3), dp(6), dp(3), dp(7)) }
    private fun action(title: String, subtitle: String, click: () -> Unit) = Button(this).apply { text = "▸  $title\n    $subtitle"; textSize = 10f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(primaryText); setAllCaps(false); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_action); setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, dp(68)) }
    private fun refresh() { lifecycleScope.launch { val snapshot = withContext(Dispatchers.IO) { val db = AppDatabase.getInstance(applicationContext); Triple(db.objectDao().count(), db.objectDao().indexedImageCount(), db.objectDao().topLabels(20)) }; health.text = "● OBJECT ENGINE: READY\nPERSISTED OBSERVATIONS: ${snapshot.first}\nIMAGES WITH OBJECTS: ${snapshot.second}\nMODEL: YOLO26n W8A32"; health.setTextColor(neon); results.text = if (snapshot.third.isEmpty()) "NO PERSISTED OBJECT OBSERVATIONS\n\nRun image indexing or the model test above." else snapshot.third.joinToString("\n") { "${it.label.padEnd(22, ' ')}  ${it.total} observations" } } }
    private fun showObjectSearch() {
        val input = EditText(this).apply { hint = "person / car / phone / dog …"; setTextColor(primaryText); setHintTextColor(muted); setSingleLine(true) }
        android.app.AlertDialog.Builder(this).setTitle("OBJECT EVIDENCE SEARCH").setView(input).setNegativeButton("CANCEL", null).setPositiveButton("SEARCH") { _, _ -> lifecycleScope.launch { val label = input.text.toString().trim().lowercase(); if (label.isBlank()) return@launch; val rows = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext).objectDao().searchEvidence(label, 100) }; results.text = if (rows.isEmpty()) "NO EVIDENCE FOUND / $label" else rows.joinToString("\n\n") { "${it.imageFileName}\nIMAGE #${it.imageId}\n${it.label} / ${it.arabicLabel}\nCONFIDENCE ${(it.confidence * 100f).roundToInt()}%\nBOX ${it.left.roundToInt()},${it.top.roundToInt()} → ${it.right.roundToInt()},${it.bottom.roundToInt()}\nURI ${it.imageUri}" } } }.show()
    }
    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(9)) }
}
