package com.example.personalmemoryai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.vision.FaceSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Identity retrieval command console with evidence-oriented result cards. */
class FaceSearchActivity : AppCompatActivity() {
    private val bg = Color.rgb(5, 11, 17)
    private val panel = Color.rgb(12, 24, 34)
    private val text = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val red = Color.rgb(255, 48, 79)
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) runSearch(uri) }
    private lateinit var resultHost: LinearLayout
    private lateinit var status: TextView
    private var service: FaceSearchService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = FaceSearchService(applicationContext)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(18)); setBackgroundColor(bg) }
        root.addView(header())
        val scroll = ScrollView(this)
        resultHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        scroll.addView(resultHost)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setTextColor(neon); textSize = 10f; typeface = Typeface.MONOSPACE; text = "● READY  /  SELECT QUERY IMAGE"; setPadding(0, dp(8), 0, 0) }
        root.addView(status)
        setContentView(root)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
        addView(TextView(this@FaceSearchActivity).apply { text = "◈ IDENTITY MATCH / EVIDENCE CONSOLE"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@FaceSearchActivity).apply { text = "FACE SEARCH"; textSize = 27f; setTextColor(text); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(2)) })
        addView(TextView(this@FaceSearchActivity).apply { text = "MULTI-SIGNAL RETRIEVAL  •  LOCAL INFERENCE  •  RANKED EVIDENCE"; textSize = 9f; setTextColor(muted) })
        addView(TextView(this@FaceSearchActivity).apply { text = "IDENTITY + 478-LANDMARK SHAPE + HEAD POSE + QUALITY"; textSize = 9f; setTextColor(cyan); setPadding(0, dp(7), 0, 0) })
        addView(Button(this@FaceSearchActivity).apply { text = "SELECT QUERY IMAGE"; textSize = 10f; setTextColor(Color.rgb(5, 20, 10)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_button); setOnClickListener { picker.launch("image/*") }; layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) } })
    }

    private fun runSearch(uri: android.net.Uri) {
        resultHost.removeAllViews(); status.text = "● RUNNING  /  ANALYZING QUERY FACE..."
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { service!!.search(uri, 50) }
                status.text = "● COMPLETE  /  ${results.size} RANKED CANDIDATES"
                if (results.isEmpty()) { addMessage("NO MATCH CANDIDATES", "لا توجد وجوه قابلة للمقارنة مع الفهرس الحالي.", false); return@launch }
                results.forEachIndexed { index, result -> addCard(index + 1, result) }
            } catch (t: Throwable) {
                status.text = "● CRITICAL  /  SEARCH FAILED"
                addMessage("DIAGNOSTIC FAILURE", t.message ?: t.javaClass.simpleName, true)
            }
        }
    }

    private fun addCard(rank: Int, result: FaceSearchService.FaceMatch) {
        val title = result.person?.displayName?.takeIf { !it.isNullOrBlank() } ?: "PERSON CLUSTER #${result.person?.id ?: "UNASSIGNED"}"
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) } }
        val preview = ImageView(this).apply { setImageURI(android.net.Uri.parse(result.image.uri)); scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = result.image.fileName; layoutParams = LinearLayout.LayoutParams(-1, dp(180)) }
        card.addView(preview)
        card.addView(TextView(this).apply { text = String.format(Locale.US, "#%02d  %s", rank, title); textSize = 16f; setTextColor(text); setTypeface(null, Typeface.BOLD); setPadding(0, dp(8), 0, 0) })
        val scoreColor = if (result.compositeScore >= 0.85f) neon else if (result.compositeScore >= 0.65f) cyan else Color.rgb(255, 193, 72)
        card.addView(TextView(this).apply { text = String.format(Locale.US, "OVERALL  %.1f%%", result.compositeScore * 100f); textSize = 18f; setTextColor(scoreColor); setTypeface(null, Typeface.BOLD); setPadding(0, dp(6), 0, 0) })
        card.addView(TextView(this).apply { text = String.format(Locale.US, "IDENTITY %.1f%%   •   SHAPE %.1f%%\nPOSE %.1f%%   •   QUALITY %.1f%%\nCONFIDENCE BAND  %s", result.identitySimilarity * 100f, result.shapeSimilarity * 100f, result.poseSimilarity * 100f, result.quality * 100f, result.confidenceBand.name); textSize = 10f; setTextColor(cyan); setPadding(0, dp(5), 0, dp(7)) })
        card.addView(TextView(this).apply { text = "FACE #${result.face.id}  •  IMAGE #${result.image.id}\n${result.image.fileName}\n${result.image.filePath ?: result.image.uri}"; textSize = 10f; setTextColor(muted) })
        card.setOnClickListener { ImageViewerActivity.start(this, result.image.uri) }
        resultHost.addView(card)
    }

    private fun addMessage(title: String, message: String, critical: Boolean) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundResource(if (critical) com.example.personalmemoryai.R.drawable.bg_critical_alert else com.example.personalmemoryai.R.drawable.bg_neon_panel) }
        card.addView(TextView(this).apply { text = title; textSize = 14f; setTextColor(if (critical) red else text); setTypeface(null, Typeface.BOLD) })
        card.addView(TextView(this).apply { text = message; textSize = 11f; setTextColor(if (critical) Color.rgb(255, 180, 190) else muted); setPadding(0, dp(6), 0, 0) })
        resultHost.addView(card)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() { service?.close(); service = null; super.onDestroy() }
}
