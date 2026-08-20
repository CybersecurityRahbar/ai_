package com.example.personalmemoryai.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.indexing.OcrSearchResult
import com.example.personalmemoryai.indexing.OcrSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class OcrEvidenceActivity : AppCompatActivity() {
    private val textColor = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val amber = Color.rgb(255, 193, 72)
    private val red = Color.rgb(255, 48, 79)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(18)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        root.addView(header())
        val query = EditText(this).apply { hint = "اكتب النص المراد البحث عنه…"; setTextColor(textColor); setHintTextColor(muted); textSize = 16f; setSingleLine(true); setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel) }
        root.addView(query, margin(0, 0, 0, 8))
        val search = Button(this).apply { text = "▸  SEARCH OCR EVIDENCE"; textSize = 10f; setTextColor(textColor); setAllCaps(false); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_action); setOnClickListener { execute(query, this@apply) } }
        root.addView(search, margin(0, 0, 0, 9))
        val summary = TextView(this).apply { text = "READY / OCR RETRIEVAL"; textSize = 10f; setTextColor(neon); setTypeface(null, Typeface.BOLD); setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel) }
        root.addView(summary, margin(0, 0, 0, 8))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)
        search.setOnClickListener {
            val q = query.text?.toString().orEmpty().trim()
            if (q.isBlank()) { summary.text = "INPUT REQUIRED / ENTER OCR QUERY"; summary.setTextColor(red); results.removeAllViews(); return@setOnClickListener }
            search.isEnabled = false; summary.text = "◉ SEARCHING / OCR INDEX"; summary.setTextColor(cyan); results.removeAllViews()
            lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) { OcrSearchService(applicationContext).search(q, 100) }
                summary.text = if (found.isEmpty()) "NO OCR EVIDENCE / $q" else "● ${found.size} RESULTS / RELEVANCE + QUALITY + MATCHED CHARACTERS"
                summary.setTextColor(if (found.isEmpty()) amber else neon)
                found.forEachIndexed { index, result -> results.addView(card(index + 1, result), margin(0, 0, 0, 9)) }
                search.isEnabled = true
            }
        }
    }

    private fun execute(query: EditText, ignored: Button) { query.requestFocus() }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(5).toFloat()
        addView(TextView(this@OcrEvidenceActivity).apply { text = "◈ TEXT INTELLIGENCE / EVIDENCE"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@OcrEvidenceActivity).apply { text = "OCR INTELLIGENCE"; textSize = 27f; setTextColor(textColor); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(2)) })
        addView(TextView(this@OcrEvidenceActivity).apply { text = "ARABIC • LATIN • NORMALIZATION • QUALITY • CONFIDENCE"; textSize = 9f; setTextColor(muted) })
    }

    private fun card(rank: Int, result: OcrSearchResult): LinearLayout {
        val image = result.image
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(2).toFloat() }
        card.addView(TextView(this).apply { text = "#$rank  ${image.fileName}"; textSize = 15f; setTextColor(textColor); setTypeface(null, Typeface.BOLD) })
        card.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; try { setImageURI(Uri.parse(image.uri)) } catch (_: Throwable) {} }, LinearLayout.LayoutParams(-1, dp(170)).apply { setMargins(0, dp(8), 0, dp(8)) })
        val bandColor = when (result.confidenceBand) { "VERY_HIGH", "HIGH" -> neon; "MEDIUM" -> amber; else -> red }
        card.addView(TextView(this).apply { text = "RELEVANCE ${percent(result.relevanceScore)}   OCR ${percent(result.qualityScore)}   ${result.confidenceBand}"; textSize = 10f; setTextColor(bandColor); setTypeface(null, Typeface.BOLD) })
        card.addView(TextView(this).apply { text = "MATCHED ${result.matchedCharacters}   •   LANGUAGE ${image.ocrLanguage}"; textSize = 10f; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
        card.addView(TextView(this).apply { text = "PASSES ${image.ocrSuccessfulPasses}/${image.ocrPassCount}   •   ARABIC ${image.ocrArabicCharacters}   •   LATIN ${image.ocrLatinCharacters}"; textSize = 10f; setTextColor(muted) })
        card.addView(TextView(this).apply { text = "REASON  ${result.reason}"; textSize = 10f; setTextColor(Color.rgb(178,204,220)); setPadding(0, dp(5), 0, dp(4)) })
        card.addView(TextView(this).apply { text = image.ocrText.ifBlank { "[NO OCR TEXT]" }; textSize = 14f; setTextColor(textColor); setPadding(dp(10), dp(9), dp(10), dp(9)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel) })
        card.addView(TextView(this).apply { text = "PATH  ${image.filePath ?: image.uri}"; textSize = 9f; setTextColor(muted); setPadding(0, dp(7), 0, 0) })
        card.setOnClickListener { startActivity(Intent(this, ImageViewerActivity::class.java).apply { putExtra("image_uri", image.uri); putExtra("image_id", image.id) }) }
        return card
    }
    private fun percent(v: Float) = String.format(Locale.US, "%.1f%%", v.coerceIn(0f, 1f) * 100f)
    private fun margin(l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
}
