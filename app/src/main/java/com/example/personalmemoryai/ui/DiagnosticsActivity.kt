package com.example.personalmemoryai.ui

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.personalmemoryai.diagnostics.DiagnosticsManager

class DiagnosticsActivity : AppCompatActivity() {
    private val bg = Color.rgb(7, 14, 22)
    private val panel = Color.rgb(15, 27, 39)
    private val text = Color.rgb(232, 244, 252)
    private val muted = Color.rgb(119, 151, 174)
    private val accent = Color.rgb(25, 91, 133)
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private val diagnostics by lazy { DiagnosticsManager.get(applicationContext) }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(bg) }
        root.addView(TextView(this).apply {
            text = "SYSTEM DIAGNOSTICS / HEALTH CORE"
            textSize = 12f; setTextColor(Color.rgb(143, 211, 255)); setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "تشخيص الفهرسة • البحث • الوجوه • الكائنات • OCR • MobileCLIP • النسخ الاحتياطي • الأعطال"
            textSize = 20f; setTextColor(text); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, dp(6), 0, dp(10))
        })
        statusView = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(91, 221, 174)); setBackgroundColor(panel); setPadding(dp(14), dp(14), dp(14), dp(14)) }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("REFRESH") { refresh() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, 0, dp(4), 0) })
        actions.addView(button("CLEAR LOG") { diagnostics.clear(); refresh() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        actions.addView(button("CLOSE") { finish() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, 0, 0) })
        root.addView(actions)

        val scroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 11f; setTextColor(text); setBackgroundColor(Color.rgb(10, 20, 30)); setPadding(dp(12), dp(12), dp(12), dp(20));
            typeface = android.graphics.Typeface.MONOSPACE; gravity = Gravity.START
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(10), 0, 0) })
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        val events = diagnostics.readLatest(250)
        val errors = events.count { it.contains("\"severity\":\"ERROR\"") || it.contains("\"severity\":\"CRITICAL\"") }
        val warnings = events.count { it.contains("\"severity\":\"WARNING\"") }
        statusView.text = "MONITORING ONLINE  •  EVENTS ${events.size}  •  ERRORS $errors  •  WARNINGS $warnings  •  JOURNAL ${diagnostics.sizeBytes()} B"
        logView.text = if (events.isEmpty()) "NO DIAGNOSTIC EVENTS YET\n\nابدأ عملية فهرسة أو بحث وسيظهر مسار التنفيذ هنا." else events.asReversed().joinToString("\n")
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 10f; setTextColor(text); setBackgroundColor(accent); setAllCaps(false); setOnClickListener { click() }
    }
}
