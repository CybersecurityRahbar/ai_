package com.example.personalmemoryai.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
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
    private val bg = Color.rgb(7, 13, 20)
    private val panel = Color.rgb(14, 25, 37)
    private val panel2 = Color.rgb(18, 32, 46)
    private val textColor = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(143, 166, 184)
    private val accent = Color.rgb(38, 126, 177)
    private val green = Color.rgb(72, 210, 166)
    private val amber = Color.rgb(238, 181, 79)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(18))
            setBackgroundColor(bg)
        }
        root.addView(TextView(this).apply {
            text = "OCR EVIDENCE CONSOLE"
            textSize = 11f
            setTextColor(Color.rgb(143, 211, 255))
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "بحث نصي مع أدلة الجودة والثقة"
            textSize = 23f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(3))
        })
        root.addView(TextView(this).apply {
            text = "Arabic normalization • fuzzy retrieval • OCR quality • pass telemetry"
            textSize = 9f
            setTextColor(muted)
        })

        val query = EditText(this).apply {
            hint = "اكتب النص المراد البحث عنه…"
            setTextColor(textColor)
            setHintTextColor(muted)
            textSize = 16f
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(panel2)
        }
        root.addView(query, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(14), 0, dp(8)) })

        val search = Button(this).apply {
            text = "SEARCH OCR EVIDENCE"
            setTextColor(textColor)
            setBackgroundColor(accent)
            setAllCaps(false)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, 0, 0, dp(10)) })

        val summary = TextView(this).apply {
            text = "جاهز للبحث. النتائج ستعرض سبب الترتيب وجودة OCR وعدد محاولات الاستخراج."
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(panel)
        }
        root.addView(summary)

        val scroll = ScrollView(this)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(20)) }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        search.setOnClickListener {
            val q = query.text?.toString().orEmpty().trim()
            if (q.isBlank()) {
                summary.text = "أدخل نصًا للبحث."
                results.removeAllViews()
                return@setOnClickListener
            }
            search.isEnabled = false
            summary.text = "جاري تحليل الفهرس…"
            results.removeAllViews()
            lifecycleScope.launch {
                val found = withContext(Dispatchers.IO) { OcrSearchService(applicationContext).search(q, 100) }
                render(found, summary, results, q)
                search.isEnabled = true
            }
        }
    }

    private fun render(found: List<OcrSearchResult>, summary: TextView, container: LinearLayout, query: String) {
        summary.text = if (found.isEmpty()) {
            "لا توجد نتائج للنص: \"$query\". لم يجد محرك البحث دليل OCR ذا صلة."
        } else {
            "${found.size} نتيجة • relevance + OCR quality + matched characters"
        }
        found.forEachIndexed { index, result -> container.addView(card(index + 1, result)) }
    }

    private fun card(rank: Int, result: OcrSearchResult): LinearLayout {
        val image = result.image
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(panel)
        }
        card.addView(TextView(this).apply {
            text = "#$rank  ${image.fileName}"
            textSize = 15f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(4, 9, 14))
            try { setImageURI(Uri.parse(image.uri)) } catch (_: Throwable) {}
        }, LinearLayout.LayoutParams(-1, dp(170)).apply { setMargins(0, dp(8), 0, dp(8)) })

        val bandColor = when (result.confidenceBand) { "VERY_HIGH", "HIGH" -> green; "MEDIUM" -> amber; else -> Color.LTGRAY }
        card.addView(TextView(this).apply {
            text = "RELEVANCE ${percent(result.relevanceScore)}   OCR QUALITY ${percent(result.qualityScore)}   ${result.confidenceBand}"
            textSize = 11f
            setTextColor(bandColor)
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "MATCHED CHARACTERS: ${result.matchedCharacters}    LANGUAGE: ${image.ocrLanguage}"
            textSize = 10f
            setTextColor(muted)
            setPadding(0, dp(5), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "OCR PASSES: ${image.ocrSuccessfulPasses}/${image.ocrPassCount}    ARABIC: ${image.ocrArabicCharacters}    LATIN: ${image.ocrLatinCharacters}"
            textSize = 10f
            setTextColor(muted)
        })
        card.addView(TextView(this).apply {
            text = "REASON: ${result.reason}"
            textSize = 10f
            setTextColor(Color.rgb(178, 204, 220))
            setPadding(0, dp(5), 0, dp(4))
        })
        card.addView(TextView(this).apply {
            text = image.ocrText.ifBlank { "[NO OCR TEXT]" }
            textSize = 14f
            setTextColor(textColor)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setBackgroundColor(panel2)
        })
        card.addView(TextView(this).apply {
            text = "PATH: ${image.filePath ?: image.uri}"
            textSize = 9f
            setTextColor(muted)
            setPadding(0, dp(7), 0, 0)
        })
        card.setOnClickListener {
            startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                putExtra("image_uri", image.uri)
                putExtra("image_id", image.id)
            })
        }
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) } }
    }

    private fun percent(value: Float) = String.format(Locale.US, "%.1f%%", value.coerceIn(0f, 1f) * 100f)
}
