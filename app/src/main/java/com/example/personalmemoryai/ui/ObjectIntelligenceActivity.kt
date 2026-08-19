package com.example.personalmemoryai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
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

/** Operational console for the persisted object-intelligence pipeline. */
class ObjectIntelligenceActivity : AppCompatActivity() {
    private val bg = Color.rgb(7, 14, 22)
    private val panel = Color.rgb(15, 28, 41)
    private val text = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(122, 157, 180)
    private val accent = Color.rgb(28, 91, 132)
    private val ok = Color.rgb(65, 205, 157)
    private lateinit var health: TextView
    private lateinit var results: TextView

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            health.text = "RUNNING OBJECT INFERENCE…\nValidating model and input image"
            try {
                val output = withContext(Dispatchers.Default) {
                    val detector = YoloObjectDetector(applicationContext)
                    try {
                        val started = System.nanoTime()
                        val detections = detector.detect(uri)
                        val elapsed = (System.nanoTime() - started) / 1_000_000L
                        detections to elapsed
                    } finally { detector.close() }
                }
                val detections = output.first
                health.text = "OBJECT ENGINE: ONLINE\nINFERENCE: SUCCESS\nDETECTIONS: ${detections.size}\nLATENCY: ${output.second} ms\nMODEL: YOLO26n W8A32"
                results.text = if (detections.isEmpty()) {
                    "NO OBJECTS ABOVE CONFIDENCE THRESHOLD\n\nThis is a valid zero-result only if model inference succeeded."
                } else detections.mapIndexed { i, d ->
                    "${i + 1}. ${d.label} / ${d.arabicLabel}   ${(d.confidence * 100f).roundToInt()}%\n   BOX  ${d.left.roundToInt()},${d.top.roundToInt()} → ${d.right.roundToInt()},${d.bottom.roundToInt()}"
                }.joinToString("\n\n")
            } catch (t: Throwable) {
                health.text = "OBJECT ENGINE: ERROR\n${t.javaClass.simpleName}: ${t.message}"
                DiagnosticsManager.get(applicationContext).begin("OBJECT_CONSOLE", mapOf("uri" to uri.toString())).failure("INFERENCE", t)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(bg) }
        root.addView(TextView(this).apply { text = "OBJECT INTELLIGENCE / ANALYSIS CONSOLE"; textSize = 12f; setTextColor(Color.rgb(139, 211, 255)); setTypeface(null, Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "كشف الكائنات • الأدلة المرئية • قياس النموذج • تتبع النتائج"; textSize = 21f; setTextColor(text); setTypeface(null, Typeface.BOLD); setPadding(0, dp(5), 0, dp(14)) })
        health = TextView(this).apply { setTextColor(ok); textSize = 12f; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(panel) }
        root.addView(health, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) })
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(action("RUN OBJECT MODEL TEST", "Select one image and execute YOLO inference now") { picker.launch("image/*") })
        content.addView(action("SEARCH OBJECT EVIDENCE", "Open indexed images containing a selected object") { showObjectSearch() })
        content.addView(TextView(this).apply { text = "PERSISTED OBJECT EVIDENCE"; textSize = 10f; setTextColor(Color.rgb(132, 188, 222)); setTypeface(null, Typeface.BOLD); setPadding(0, dp(16), 0, dp(7)) })
        results = TextView(this).apply { textSize = 12f; setTextColor(text); setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(panel); setTextIsSelectable(true) }
        content.addView(results)
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(applicationContext)
                Triple(db.objectDao().count(), db.objectDao().indexedImageCount(), db.objectDao().topLabels(20))
            }
            health.text = "OBJECT ENGINE: READY\nPERSISTED OBSERVATIONS: ${snapshot.first}\nIMAGES WITH OBJECTS: ${snapshot.second}\nMODEL: YOLO26n W8A32"
            results.text = if (snapshot.third.isEmpty()) "No persisted object observations yet. Run image indexing or the model test above." else snapshot.third.joinToString("\n") { "${it.label.padEnd(22, ' ')}  ${it.total} observations" }
        }
    }

    private fun showObjectSearch() {
        val input = android.widget.EditText(this).apply { hint = "person / car / phone / dog …"; setTextColor(text); setHintTextColor(muted) }
        android.app.AlertDialog.Builder(this).setTitle("OBJECT EVIDENCE SEARCH").setView(input).setNegativeButton("CANCEL", null).setPositiveButton("SEARCH") { _, _ ->
            lifecycleScope.launch {
                val label = input.text.toString().trim().lowercase()
                if (label.isBlank()) return@launch
                val rows = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext).objectDao().searchEvidence(label, 100) }
                results.text = if (rows.isEmpty()) "No indexed evidence for: $label" else rows.joinToString("\n\n") { "${it.imageFileName}\nIMAGE #${it.imageId}\n${it.label} / ${it.arabicLabel}\nConfidence ${(it.confidence * 100f).roundToInt()}%\nBox ${it.left.roundToInt()},${it.top.roundToInt()} → ${it.right.roundToInt()},${it.bottom.roundToInt()}\nURI: ${it.imageUri}" }
            }
        }.show()
    }

    private fun action(title: String, subtitle: String, click: () -> Unit) = Button(this).apply {
        text = "$title\n$subtitle"; textSize = 11f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(text); setAllCaps(false); setBackgroundColor(accent); setPadding(dp(16), dp(10), dp(16), dp(10)); setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(64)).apply { setMargins(0, 0, 0, dp(7)) }
    }
}
