package com.example.personalmemoryai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.intelligence.CompositeEvidenceMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Composite investigation console.
 *
 * This screen is intentionally evidence-oriented: it presents the outputs of
 * the existing fusion engine without introducing another inference pipeline.
 * Text Encoder is not used here; semantic evidence remains image-to-image.
 */
class InvestigationActivity : AppCompatActivity() {
    private val text = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val violet = Color.rgb(179, 107, 255)
    private val amber = Color.rgb(255, 200, 87)
    private val red = Color.rgb(255, 48, 79)

    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private var matcher: CompositeEvidenceMatcher? = null

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runInvestigation(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        matcher = CompositeEvidenceMatcher(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence)
        }
        root.addView(header())
        status = TextView(this).apply {
            text = "● READY  /  SELECT QUERY EVIDENCE"
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextColor(neon)
            setPadding(dp(6), dp(9), dp(6), dp(3))
        }
        root.addView(status)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
        elevation = dp(5).toFloat()
        addView(TextView(this@InvestigationActivity).apply {
            text = "◈ COMPOSITE EVIDENCE / INVESTIGATION CONSOLE"
            textSize = 10f
            setTextColor(cyan)
            setTypeface(null, Typeface.BOLD)
        })
        addView(TextView(this@InvestigationActivity).apply {
            text = "EVIDENCE FUSION"
            textSize = 27f
            setTextColor(text)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(2))
        })
        addView(TextView(this@InvestigationActivity).apply {
            text = "FACE • BODY • POSE • APPEARANCE • SCENE • VISUAL • OCR • OBJECTS"
            textSize = 8f
            setTextColor(muted)
        })
        addView(TextView(this@InvestigationActivity).apply {
            text = "LOCAL INFERENCE  •  EVIDENCE COVERAGE  •  RANKED CORROBORATION"
            textSize = 8f
            setTextColor(violet)
            setPadding(0, dp(7), 0, 0)
        })
        addView(Button(this@InvestigationActivity).apply {
            text = "▸  SELECT QUERY IMAGE"
            textSize = 10f
            setAllCaps(false)
            setTextColor(text)
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_action)
            setOnClickListener { picker.launch("image/*") }
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) }
        })
    }

    private fun runInvestigation(uri: Uri) {
        results.removeAllViews()
        status.text = "◉ RUNNING  /  BUILDING COMPOSITE EVIDENCE..."
        status.setTextColor(cyan)
        addSection("QUERY EVIDENCE", cyan)
        addText("Query selected. The existing Stage-5 fusion engine will independently evaluate available signals and exclude unavailable evidence instead of treating it as a zero match.")

        lifecycleScope.launch {
            try {
                val started = System.nanoTime()
                val matches = withContext(Dispatchers.IO) { matcher!!.search(uri, 30) }
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                status.text = "● COMPLETE  /  ${matches.size} CORROBORATED CANDIDATES  /  ${elapsed} ms"
                status.setTextColor(neon)
                if (matches.isEmpty()) {
                    addMessage("NO COMPOSITE CANDIDATES", "No visual candidates were available for fusion. Check indexing and model health before retrying.", false)
                    return@launch
                }
                addSection("CORROBORATED EVIDENCE", cyan)
                addSummary(matches.size, matches.first().confidence, matches.first().evidenceCoverage)
                matches.forEachIndexed { index, match -> addMatch(index + 1, match) }
            } catch (t: Throwable) {
                status.text = "● CRITICAL  /  COMPOSITE SEARCH FAILED"
                status.setTextColor(red)
                addMessage("DIAGNOSTIC FAILURE", t.message ?: t.javaClass.simpleName, true)
            }
        }
    }

    private fun addSummary(count: Int, topConfidence: Float, coverage: Float) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundResource(com.example.personalmemoryai.R.drawable.panel_intelligence)
        }
        val values = listOf(
            "CANDIDATES" to count.toString(),
            "TOP CONF." to String.format(Locale.US, "%.1f%%", topConfidence * 100f),
            "COVERAGE" to String.format(Locale.US, "%.1f%%", coverage * 100f)
        )
        values.forEach { (label, value) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), dp(4), dp(7), dp(4))
                addView(TextView(this@InvestigationActivity).apply { text = label; textSize = 7f; setTextColor(muted) })
                addView(TextView(this@InvestigationActivity).apply { text = value; textSize = 18f; setTextColor(cyan); setTypeface(null, Typeface.BOLD); setPadding(0, dp(2), 0, 0) })
            }
            panel.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        results.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun addMatch(rank: Int, match: CompositeEvidenceMatcher.CompositeMatch) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        card.addView(ImageView(this).apply {
            setImageURI(Uri.parse(match.image.uri))
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = match.image.fileName
            layoutParams = LinearLayout.LayoutParams(-1, dp(180))
        })
        card.addView(TextView(this).apply {
            text = String.format(Locale.US, "#%02d  %s", rank, match.image.fileName)
            textSize = 15f
            setTextColor(text)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        })
        val bandColor = when (match.band) {
            CompositeEvidenceMatcher.MatchBand.VERY_HIGH -> neon
            CompositeEvidenceMatcher.MatchBand.HIGH -> cyan
            CompositeEvidenceMatcher.MatchBand.MEDIUM -> amber
            else -> muted
        }
        card.addView(TextView(this).apply {
            text = String.format(Locale.US, "COMPOSITE %.1f%%   •   CONFIDENCE %.1f%%   •   %s", match.compositeScore * 100f, match.confidence * 100f, match.band.name)
            textSize = 12f
            setTextColor(bandColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(5))
        })
        addEvidenceGrid(card, match)
        card.addView(TextView(this).apply {
            text = "COVERAGE  ${String.format(Locale.US, "%.1f%%", match.evidenceCoverage * 100f)}   •   QUALITY  ${String.format(Locale.US, "%.1f%%", match.evidenceQuality * 100f)}\nIMAGE #${match.image.id}  •  ${match.image.filePath ?: match.image.uri}"
            textSize = 9f
            setTextColor(muted)
            setPadding(0, dp(7), 0, 0)
        })
        card.setOnClickListener { ImageViewerActivity.start(this, match.image.uri) }
        results.addView(card)
    }

    private fun addEvidenceGrid(card: LinearLayout, match: CompositeEvidenceMatcher.CompositeMatch) {
        val keys = listOf("face" to "FACE", "body" to "BODY", "pose" to "POSE", "clothing" to "CLOTHING", "scene" to "SCENE", "visual" to "VISUAL", "ocr" to "OCR", "objects" to "OBJECTS")
        val rows = keys.chunked(2)
        rows.forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { (key, label) ->
                val component = match.components[key]
                val available = component?.available == true
                val value = if (available) String.format(Locale.US, "%.0f%%", (component!!.score * 100f).coerceIn(0f, 100f)) else "N/A"
                val accent = if (available) cyan else muted
                val cell = TextView(this).apply {
                    text = "$label  $value"
                    textSize = 9f
                    setTextColor(accent)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel)
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            card.addView(row)
        }
    }

    private fun addSection(label: String, accent: Int) {
        results.addView(TextView(this).apply {
            text = "▌  $label"
            textSize = 10f
            setTextColor(accent)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(3), dp(9), dp(3), dp(6))
        })
    }

    private fun addText(message: String) = results.addView(TextView(this).apply {
        text = message
        textSize = 10f
        setTextColor(muted)
        setPadding(dp(12), dp(9), dp(12), dp(12))
        setBackgroundResource(com.example.personalmemoryai.R.drawable.panel_intelligence)
    })

    private fun addMessage(title: String, message: String, critical: Boolean) {
        results.addView(TextView(this).apply {
            text = "$title\n$message"
            textSize = 10f
            setTextColor(if (critical) red else amber)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(if (critical) com.example.personalmemoryai.R.drawable.bg_critical_alert else com.example.personalmemoryai.R.drawable.bg_neon_panel)
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        matcher?.close()
        matcher = null
        super.onDestroy()
    }
}
