package com.example.personalmemoryai.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import com.example.personalmemoryai.vision.FaceSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FaceSearchActivity : AppCompatActivity() {
    private val primaryText = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val red = Color.rgb(255, 48, 79)
    private val amber = Color.rgb(255, 193, 72)
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) runSearch(uri) }
    private lateinit var resultHost: LinearLayout
    private lateinit var status: TextView
    private lateinit var telemetry: TextView
    private var service: FaceSearchService? = null
    private val diagnostics by lazy { DiagnosticsManager.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = FaceSearchService(applicationContext)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(14)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence) }
        root.addView(header())
        telemetry = TextView(this).apply { text = "FACE ENGINE READY  •  LOCAL INFERENCE  •  MULTI-SIGNAL RANKING"; textSize = 9f; setTextColor(neon); typeface = Typeface.MONOSPACE; setPadding(dp(6), dp(9), dp(6), dp(2)) }
        root.addView(telemetry)
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        resultHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(18)) }
        scroll.addView(resultHost); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setTextColor(neon); textSize = 10f; typeface = Typeface.MONOSPACE; text = "● READY  /  SELECT QUERY IMAGE"; setPadding(dp(4), dp(8), dp(4), 0) }
        root.addView(status); setContentView(root)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(5).toFloat()
        addView(TextView(this@FaceSearchActivity).apply { text = "◈ IDENTITY MATCH / EVIDENCE CONSOLE"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@FaceSearchActivity).apply { text = "FACE SEARCH"; textSize = 27f; setTextColor(primaryText); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(2)) })
        addView(TextView(this@FaceSearchActivity).apply { text = "MULTI-SIGNAL RETRIEVAL  •  LOCAL INFERENCE  •  RANKED EVIDENCE"; textSize = 9f; setTextColor(muted) })
        addView(TextView(this@FaceSearchActivity).apply { text = "IDENTITY + 478-LANDMARK SHAPE + HEAD POSE + QUALITY"; textSize = 9f; setTextColor(cyan); setPadding(0, dp(7), 0, 0) })
        addView(Button(this@FaceSearchActivity).apply { text = "▸  SELECT QUERY IMAGE"; textSize = 10f; setAllCaps(false); setTextColor(primaryText); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_action); setOnClickListener { picker.launch("image/*") }; layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) } })
    }

    private fun runSearch(uri: android.net.Uri) {
        resultHost.removeAllViews(); status.text = "◉ RUNNING  /  ANALYZING QUERY FACE..."; status.setTextColor(cyan); telemetry.text = "FACE DETECTION → LANDMARKS → EMBEDDING → SHAPE → RANKING"; telemetry.setTextColor(cyan)
        val run = diagnostics.begin("FACE_SEARCH_UI", mapOf("queryUri" to uri.toString()))
        lifecycleScope.launch {
            try {
                val started = System.nanoTime(); val results = withContext(Dispatchers.IO) { service!!.search(uri, 50) }; val elapsed = (System.nanoTime() - started) / 1_000_000L
                status.text = "● COMPLETE  /  ${results.size} RANKED CANDIDATES  /  ${elapsed} ms"; status.setTextColor(neon); telemetry.text = "SEARCH COMPLETE  •  ${results.size} CANDIDATES  •  ${elapsed} ms  •  LOCAL-ONLY"; telemetry.setTextColor(neon)
                run.success("Face search completed", mapOf("candidates" to results.size.toString(), "latencyMs" to elapsed.toString()))
                if (results.isEmpty()) { addMessage("NO MATCH CANDIDATES", "لا توجد وجوه قابلة للمقارنة مع الفهرس الحالي.", false); return@launch }
                results.forEachIndexed { index, result -> addCard(index + 1, result) }
            } catch (t: Throwable) { status.text = "● CRITICAL  /  SEARCH FAILED"; status.setTextColor(red); telemetry.text = "FACE SEARCH FAILURE  •  CHECK DIAGNOSTICS JOURNAL"; telemetry.setTextColor(red); run.failure("FACE_SEARCH", t); addMessage("DIAGNOSTIC FAILURE", t.message ?: t.javaClass.simpleName, true) }
        }
    }

    private fun addCard(rank: Int, result: FaceSearchService.FaceMatch) {
        val title = result.person?.displayName?.takeIf { !it.isNullOrBlank() } ?: "PERSON CLUSTER #${result.person?.id ?: "UNASSIGNED"}"
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(3).toFloat(); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
        card.addView(ImageView(this).apply { setImageURI(android.net.Uri.parse(result.image.uri)); scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = result.image.fileName; layoutParams = LinearLayout.LayoutParams(-1, dp(190)) })
        card.addView(TextView(this).apply { text = String.format(Locale.US, "#%02d  %s", rank, title); textSize = 16f; setTextColor(primaryText); setTypeface(null, Typeface.BOLD); setPadding(0, dp(8), 0, 0) })
        val scoreColor = if (result.compositeScore >= 0.85f) neon else if (result.compositeScore >= 0.65f) cyan else amber
        card.addView(TextView(this).apply { text = String.format(Locale.US, "OVERALL  %.1f%%", result.compositeScore * 100f); textSize = 19f; setTextColor(scoreColor); setTypeface(null, Typeface.BOLD); setPadding(0, dp(6), 0, dp(2)) })
        card.addView(View(this).apply { setBackgroundColor(scoreColor); alpha = 0.85f; layoutParams = LinearLayout.LayoutParams(dp((result.compositeScore.coerceIn(0f, 1f) * 100f).toInt().coerceAtLeast(2)), dp(3)).apply { bottomMargin = dp(7) } })
        card.addView(TextView(this).apply { text = String.format(Locale.US, "IDENTITY %.1f%%   •   SHAPE %.1f%%\nPOSE %.1f%%   •   QUALITY %.1f%%\nCONFIDENCE BAND  %s", result.identitySimilarity * 100f, result.shapeSimilarity * 100f, result.poseSimilarity * 100f, result.quality * 100f, result.confidenceBand.name); textSize = 10f; setTextColor(cyan); setPadding(0, dp(5), 0, dp(7)) })
        card.addView(TextView(this).apply { text = "FACE #${result.face.id}  •  IMAGE #${result.image.id}\n${result.image.fileName}\n${result.image.filePath ?: result.image.uri}"; textSize = 10f; setTextColor(muted) })
        card.setOnClickListener { ImageViewerActivity.start(this, result.image.uri) }; card.alpha = 0f; resultHost.addView(card); card.animate().alpha(1f).setDuration(240L).start()
    }

    private fun addMessage(title: String, message: String, critical: Boolean) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); elevation = dp(3).toFloat(); setBackgroundResource(if (critical) com.example.personalmemoryai.R.drawable.bg_critical_alert else com.example.personalmemoryai.R.drawable.bg_neon_panel) }
        card.addView(TextView(this).apply { text = title; textSize = 14f; setTextColor(if (critical) red else primaryText); setTypeface(null, Typeface.BOLD) })
        card.addView(TextView(this).apply { text = message; textSize = 11f; setTextColor(if (critical) Color.rgb(255, 180, 190) else muted); setPadding(0, dp(6), 0, 0) })
        resultHost.addView(card)
        if (critical) ValueAnimator.ofFloat(0.55f, 1f, 0.55f).apply { duration = 900L; repeatCount = 2; addUpdateListener { card.alpha = it.animatedValue as Float }; start() }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { service?.close(); service = null; super.onDestroy() }
}
