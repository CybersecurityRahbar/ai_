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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_intelligence)
        }
        root.addView(header())

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val overview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(title("LIVE INTELLIGENCE OVERVIEW", green))
        content.addView(overview)

        val images = metricCard("IMAGES", "—", cyan)
        val faces = metricCard("FACES", "—", green)
        val people = metricCard("SUBJECTS", "—", violet)
        val objects = metricCard("OBJECTS", "—", amber)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(images, faces).forEach { row1.addView(it, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(people, objects).forEach { row2.addView(it, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
        overview.addView(row1)
        overview.addView(row2)

        val health = healthPanel()
        content.addView(health, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(10), 0, dp(8)) })

        val engineMatrix = engineMatrix()
        content.addView(title("LIVE ENGINE MATRIX", cyan))
        content.addView(engineMatrix, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })

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
        content.addView(action("SYSTEM DIAGNOSTICS", "Errors • warnings • stages • stack traces • execution journal", red) { startActivity(Intent(this, DiagnosticsActivity::class.java) })

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val diagnostics = DiagnosticsManager.get(applicationContext)
            val values = withContext(Dispatchers.IO) {
                longArrayOf(
                    db.imageDao().count().toLong(),
                    db.faceDao().count(),
                    db.personDao().count(),
                    db.embeddingDao().count(),
                    db.objectDao().count()
                )
            }
            val events = withContext(Dispatchers.IO) { diagnostics.readLatest(500) }
            val errors = events.count { it.contains("\"severity\":\"ERROR\"") || it.contains("\"severity\":\"CRITICAL\"") }
            val warnings = events.count { it.contains("\"severity\":\"WARNING\"") }

            images.findMetricValue()?.text = values[0].toString()
            faces.findMetricValue()?.text = values[1].toString()
            people.findMetricValue()?.text = values[2].toString()
            objects.findMetricValue()?.text = values[4].toString()

            val clip = MobileClipModelManager(applicationContext)
            val clipState = if (clip.isInstalled()) "ONLINE / READY" else "NOT CONFIGURED"
            val faceState = if (values[1] > 0L) "ONLINE / DATA" else "IDLE / NO FACE DATA"
            val objectState = if (values[4] > 0L) "ONLINE / DATA" else "IDLE / NO OBJECT DATA"
            val healthState = when {
                errors > 0 -> "CRITICAL / $errors ERRORS"
                warnings > 0 -> "DEGRADED / $warnings WARNINGS"
                else -> "NOMINAL"
            }

            health.text = "● SYSTEM HEALTH  /  LOCAL CORE  /  $healthState\n\n" +
                "FACE ENGINE       $faceState\n" +
                "PEOPLE CLUSTERS   ${values[2]}\n" +
                "OBJECT ENGINE     $objectState\n" +
                "MOBILECLIP-S2     $clipState\n" +
                "TOTAL EMBEDDINGS  ${values[3]}\n" +
                "INDEXED IMAGES    ${values[0]}\n" +
                "TRACE EVENTS      ${events.size}\n" +
                "ERRORS            $errors\n" +
                "WARNINGS          $warnings\n\n" +
                "Open SYSTEM DIAGNOSTICS for event-level evidence."

            health.setTextColor(if (errors > 0) red else textColor)
            pulse(health)

            updateEngineMatrix(engineMatrix, values, clipState, errors, warnings)
        }
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        setBackgroundResource(R.drawable.panel_intelligence)
        elevation = dp(5).toFloat()
        addView(TextView(this@IntelligenceHomeActivity).apply {
            text = "PMAI // LOCAL INTELLIGENCE CORE"
            textSize = 10f
            setTextColor(green)
            setTypeface(null, Typeface.BOLD)
        })
        addView(TextView(this@IntelligenceHomeActivity).apply {
            text = "INTELLIGENCE COMMAND CENTER"
            textSize = 25f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        })
        addView(TextView(this@IntelligenceHomeActivity).apply {
            text = "IDENTITY  •  VISUAL  •  OCR  •  OBJECTS  •  EVIDENCE  •  TRACE"
            textSize = 9f
            setTextColor(muted)
        })
        addView(TextView(this@IntelligenceHomeActivity).apply {
            text = "●  OFFLINE / LOCAL / MONITORED"
            textSize = 10f
            setTextColor(green)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(11), 0, 0)
        })
        addView(TextView(this@IntelligenceHomeActivity).apply {
            text = SimpleDateFormat("yyyy-MM-dd  /  HH:mm:ss", Locale.US).format(Date())
            textSize = 8f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun healthPanel() = TextView(this).apply {
        text = "● SYSTEM HEALTH  /  LOCAL CORE  /  LOADING…"
        textSize = 11f
        setTextColor(textColor)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundResource(R.drawable.panel_intelligence)
        elevation = dp(3).toFloat()
    }

    private fun engineMatrix() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundResource(R.drawable.panel_intelligence)
        elevation = dp(3).toFloat()
        addView(engineRow("FACE IDENTITY", "CHECKING…", green))
        addView(engineRow("FACIAL SHAPE", "CHECKING…", green))
        addView(engineRow("MOBILECLIP-S2", "CHECKING…", cyan))
        addView(engineRow("OBJECT ANALYSIS", "CHECKING…", amber))
        addView(engineRow("ARABIC OCR", "CHECKING…", violet))
        addView(engineRow("DIAGNOSTICS", "CHECKING…", red))
    }

    private fun engineRow(name: String, state: String, accent: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(5), dp(7), dp(5), dp(7))
        addView(TextView(this@IntelligenceHomeActivity).apply {
            text = "◆  $name"
            textSize = 9f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@IntelligenceHomeActivity).apply {
            tag = "engine_state"
            text = state
            textSize = 8f
            setTextColor(accent)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.END
        })
    }

    private fun updateEngineMatrix(matrix: LinearLayout, values: LongArray, clipState: String, errors: Int, warnings: Int) {
        val states = listOf(
            if (values[1] > 0) "ONLINE / DATA" else "IDLE / NO DATA",
            if (values[1] > 0) "ONLINE / DATA" else "IDLE / NO DATA",
            clipState,
            if (values[4] > 0) "ONLINE / DATA" else "IDLE / NO DATA",
            "PIPELINE READY",
            when {
                errors > 0 -> "CRITICAL / $errors"
                warnings > 0 -> "DEGRADED / $warnings"
                else -> "NOMINAL"
            }
        )
        matrix.childrenSequence().toList().forEachIndexed { index, row ->
            row.findViewWithTag<TextView>("engine_state")?.text = states[index]
        }
    }

    private fun title(text: String, color: Int) = TextView(this).apply {
        this.text = "▌  $text"
        textSize = 10f
        setTextColor(color)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(3), dp(13), dp(3), dp(6))
    }

    private fun metricCard(label: String, value: String, accent: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(8))
        setBackgroundResource(R.drawable.panel_intelligence)
        elevation = dp(2).toFloat()
        addView(TextView(this@IntelligenceHomeActivity).apply {
            text = label
            textSize = 8f
            setTextColor(muted)
        })
        addView(TextView(this@IntelligenceHomeActivity).apply {
            tag = "metric_value"
            text = value
            textSize = 23f
            setTextColor(accent)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(3), 0, 0)
        })
        addView(TextView(this@IntelligenceHomeActivity).apply {
            text = "LIVE DATA"
            textSize = 7f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun View.findMetricValue(): TextView? = findViewWithTag("metric_value")

    private fun action(title: String, subtitle: String, accent: Int, click: () -> Unit) = Button(this).apply {
        text = "●  $title\n    $subtitle"
        textSize = 10f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(accent)
        setBackgroundResource(R.drawable.bg_intel_button)
        setAllCaps(false)
        setPadding(dp(15), dp(7), dp(15), dp(7))
        setOnClickListener { click() }
        stateListAnimator = null
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, dp(70)).apply { setMargins(0, dp(3), 0, dp(3)) }
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun pulse(view: View) {
        ValueAnimator.ofFloat(0.82f, 1f, 0.82f).apply {
            duration = 1700L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { value -> view.alpha = value.animatedValue as Float }
            start()
        }
    }
}
