package com.example.personalmemoryai.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import com.example.personalmemoryai.vision.FaceSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Dedicated functional console for multi-signal face retrieval. */
class FaceSearchActivity : AppCompatActivity() {
    private val bg = Color.rgb(8, 15, 23)
    private val panel = Color.rgb(15, 27, 39)
    private val text = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(130, 157, 177)
    private val accent = Color.rgb(27, 91, 132)
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runSearch(uri)
    }
    private lateinit var resultHost: LinearLayout
    private lateinit var status: TextView
    private var service: FaceSearchService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service = FaceSearchService(applicationContext)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(18))
            setBackgroundColor(bg)
        }
        root.addView(header())
        val scroll = ScrollView(this)
        resultHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        scroll.addView(resultHost)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setTextColor(muted); textSize = 11f; setPadding(0, dp(8), 0, 0); text = "READY • SELECT A QUERY IMAGE" }
        root.addView(status)
        setContentView(root)
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(panel)
        addView(TextView(this@FaceSearchActivity).apply {
            text = "IDENTITY MATCH CONSOLE"
            textSize = 11f
            setTextColor(Color.rgb(143, 211, 255))
            setTypeface(null, Typeface.BOLD)
        })
        addView(TextView(this@FaceSearchActivity).apply {
            text = "FACE SEARCH / MULTI-SIGNAL RETRIEVAL"
            textSize = 21f
            setTextColor(text)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        })
        addView(TextView(this@FaceSearchActivity).apply {
            text = "MobileFaceNet + 478 LANDMARK SHAPE + HEAD POSE + QUALITY"
            textSize = 9f
            setTextColor(muted)
        })
        addView(TextView(this@FaceSearchActivity).apply {
            text = "Identity is the primary signal; geometry, pose and quality provide independent corroboration."
            textSize = 9f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })
        addView(Button(this@FaceSearchActivity).apply {
            text = "SELECT QUERY IMAGE"
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)
            setOnClickListener { picker.launch("image/*") }
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) }
        })
    }

    private fun runSearch(uri: android.net.Uri) {
        resultHost.removeAllViews()
        status.text = "ANALYZING QUERY FACE..."
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { service!!.search(uri, 50) }
                status.text = "SEARCH COMPLETE • ${results.size} ranked candidates"
                if (results.isEmpty()) {
                    addMessage("NO MATCH CANDIDATES", "لا توجد وجوه قابلة للمقارنة مع الفهرس الحالي.")
                    return@launch
                }
                results.forEachIndexed { index, result -> addCard(index + 1, result) }
            } catch (t: Throwable) {
                status.text = "SEARCH FAILED • ${t.message ?: t.javaClass.simpleName}"
                addMessage("DIAGNOSTIC FAILURE", t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun addCard(rank: Int, result: FaceSearchService.FaceMatch) {
        val title = result.person?.displayName?.takeIf { !it.isNullOrBlank() } ?: "PERSON CLUSTER #${result.person?.id ?: "UNASSIGNED"}"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
        }
        val preview = ImageView(this).apply {
            setImageURI(android.net.Uri.parse(result.image.uri))
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = result.image.fileName
            layoutParams = LinearLayout.LayoutParams(-1, dp(150))
        }
        card.addView(preview)
        card.addView(TextView(this).apply {
            text = String.format(Locale.US, "#%02d  %s", rank, title)
            textSize = 15f; setTextColor(text); setTypeface(null, Typeface.BOLD); setPadding(0, dp(8), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = String.format(Locale.US, "OVERALL  %.1f%%   •   IDENTITY  %.1f%%   •   SHAPE  %.1f%%", result.compositeScore * 100f, result.identitySimilarity * 100f, result.shapeSimilarity * 100f)
            textSize = 11f; setTextColor(Color.rgb(143, 211, 255)); setPadding(0, dp(6), 0, dp(2))
        })
        card.addView(TextView(this).apply {
            text = String.format(Locale.US, "POSE  %.1f%%   •   QUALITY  %.1f%%   •   BAND  %s", result.poseSimilarity * 100f, result.quality * 100f, result.confidenceBand.name)
            textSize = 10f; setTextColor(muted); setPadding(0, dp(2), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = "FACE #${result.face.id}  •  IMAGE #${result.image.id}\n${result.image.fileName}\n${result.image.filePath ?: result.image.uri}"
            textSize = 10f; setTextColor(muted)
        })
        card.setOnClickListener { ImageViewerActivity.start(this, result.image.uri) }
        resultHost.addView(card)
    }

    private fun addMessage(title: String, message: String) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(panel) }
        card.addView(TextView(this).apply { text = title; textSize = 13f; setTextColor(text); setTypeface(null, Typeface.BOLD) })
        card.addView(TextView(this).apply { text = message; textSize = 11f; setTextColor(muted); setPadding(0, dp(6), 0, 0) })
        resultHost.addView(card)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        service?.close()
        service = null
        super.onDestroy()
    }
}
