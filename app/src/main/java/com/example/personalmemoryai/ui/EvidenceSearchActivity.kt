package com.example.personalmemoryai.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.semantic.SemanticSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EvidenceSearchActivity : AppCompatActivity() {
    private val primaryText = Color.rgb(233, 255, 244)
    private val muted = Color.rgb(127, 169, 154)
    private val green = Color.rgb(57, 255, 136)
    private val cyan = Color.rgb(53, 232, 255)
    private val violet = Color.rgb(179, 107, 255)
    private val red = Color.rgb(255, 48, 79)

    private lateinit var status: TextView
    private lateinit var results: RecyclerView
    private lateinit var query: EditText
    private lateinit var preview: ImageView
    private var selectedImage: Uri? = null
    private var semanticService: SemanticSearchService? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        selectedImage = uri
        preview.visibility = ImageView.VISIBLE
        preview.setImageURI(uri)
        status.text = "IMAGE QUERY READY / RUN VISUAL SEARCH"
        status.setTextColor(cyan)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        semanticService = SemanticSearchService(applicationContext)
        setContentView(buildScreen())
    }

    private fun buildScreen(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(14))
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence)
        }
        root.addView(header())
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(sectionTitle("IMAGE-TO-IMAGE / VISUAL RETRIEVAL", cyan))
        preview = ImageView(this).apply {
            visibility = ImageView.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel)
            layoutParams = LinearLayout.LayoutParams(-1, dp(180)).apply { setMargins(0, 0, 0, dp(7)) }
        }
        content.addView(preview)
        content.addView(button("SELECT QUERY IMAGE", cyan) { imagePicker.launch("image/*") })
        content.addView(button("RUN VISUAL SEARCH", green) { runVisualSearch() })

        content.addView(sectionTitle("OCR / OBJECT EVIDENCE RETRIEVAL", violet))
        query = EditText(this).apply {
            hint = "Search OCR text or object label…"
            setHintTextColor(muted)
            setTextColor(primaryText)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(13), dp(5), dp(13), dp(5))
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_search)
        }
        content.addView(query, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, 0, 0, dp(7)) })
        content.addView(button("SEARCH PERSISTED EVIDENCE", violet) { runKeywordSearch() })

        status = TextView(this).apply {
            text = "READY / TEXT ENCODER DEFERRED / IMAGE SEARCH AVAILABLE WHEN MODEL IS READY"
            textSize = 9f
            setTextColor(green)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(12), dp(4), dp(10))
        }
        content.addView(status)

        results = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EvidenceSearchActivity)
            isNestedScrollingEnabled = false
        }
        content.addView(results, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun runVisualSearch() {
        val uri = selectedImage
        if (uri == null) {
            status.text = "SELECT A QUERY IMAGE FIRST"
            status.setTextColor(red)
            return
        }
        lifecycleScope.launch {
            status.text = "VISUAL SEARCH / LOADING MOBILECLIP-S2…"
            status.setTextColor(cyan)
            try {
                val ranked = withContext(Dispatchers.Default) { semanticService!!.searchSimilarImages(uri, 30) }
                results.adapter = VisualResultAdapter(ranked.map { VisualRow(it.image, it.percent, it.band.name) })
                status.text = "VISUAL SEARCH COMPLETE / ${ranked.size} COMPATIBLE RESULTS"
                status.setTextColor(green)
            } catch (t: Throwable) {
                status.text = "VISUAL SEARCH UNAVAILABLE / ${t.message ?: "CHECK DIAGNOSTICS"}"
                status.setTextColor(red)
            }
        }
    }

    private fun runKeywordSearch() {
        val value = query.text.toString().trim()
        if (value.isBlank()) {
            status.text = "ENTER OCR TEXT OR AN OBJECT LABEL"
            status.setTextColor(red)
            return
        }
        lifecycleScope.launch {
            status.text = "EVIDENCE SEARCH / QUERYING OCR + OBJECT INDEX…"
            status.setTextColor(violet)
            val matches = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext).imageDao().searchTextAndObjects(value) }
            results.adapter = EvidenceResultAdapter(matches)
            status.text = "EVIDENCE SEARCH COMPLETE / ${matches.size} RESULTS"
            status.setTextColor(if (matches.isEmpty()) muted else green)
        }
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
        elevation = dp(5).toFloat()
        addView(TextView(this@EvidenceSearchActivity).apply { text = "◈ EVIDENCE RETRIEVAL / LOCAL CORE"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@EvidenceSearchActivity).apply { text = "EVIDENCE SEARCH CONSOLE"; textSize = 27f; setTextColor(primaryText); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(2)) })
        addView(TextView(this@EvidenceSearchActivity).apply { text = "VISUAL SIMILARITY  •  OCR  •  OBJECTS  •  PERSISTED EVIDENCE"; textSize = 9f; setTextColor(muted) })
    }

    private fun sectionTitle(value: String, color: Int) = TextView(this).apply {
        text = "▌  $value"; textSize = 10f; setTextColor(color); setTypeface(null, Typeface.BOLD); setPadding(dp(3), dp(13), dp(3), dp(7))
    }

    private fun button(label: String, accent: Int, action: () -> Unit) = Button(this).apply {
        text = "●  $label"; textSize = 10f; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setTextColor(accent); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_action); setAllCaps(false); setPadding(dp(15), dp(7), dp(15), dp(7)); stateListAnimator = null; elevation = dp(2).toFloat(); setOnClickListener { action() }; layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(3), 0, dp(3)) }
    }

    private inner class EvidenceResultAdapter(private val items: List<ImageEntity>) : RecyclerView.Adapter<ResultHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder = resultHolder(parent)
        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            val item = items[position]
            holder.image.setImageURI(Uri.parse(item.uri))
            holder.title.text = "${position + 1}. ${item.fileName}"
            holder.details.text = "OCR  ${if (item.ocrText.isBlank()) "NONE" else "MATCH"}  •  OBJECTS  ${if (item.detectedObjects.isBlank() || item.detectedObjects == "[]") "NONE" else "MATCH"}\n${item.width}×${item.height}  •  ${item.mimeType ?: "IMAGE"}\n${item.filePath ?: item.uri}"
            holder.itemView.setOnClickListener { ImageViewerActivity.start(this@EvidenceSearchActivity, item.uri) }
        }
        override fun getItemCount() = items.size
    }

    private inner class VisualResultAdapter(private val items: List<VisualRow>) : RecyclerView.Adapter<ResultHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder = resultHolder(parent)
        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            val row = items[position]
            holder.image.setImageURI(Uri.parse(row.image.uri))
            holder.title.text = "${position + 1}. ${row.image.fileName}  /  ${row.percent}%"
            holder.details.text = "VISUAL MATCH  ${row.band}\n${row.image.width}×${row.image.height}  •  ${row.image.filePath ?: row.image.uri}"
            holder.itemView.setOnClickListener { ImageViewerActivity.start(this@EvidenceSearchActivity, row.image.uri) }
        }
        override fun getItemCount() = items.size
    }

    private fun resultHolder(parent: ViewGroup): ResultHolder {
        val box = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(9), dp(9), dp(9), dp(9)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); layoutParams = RecyclerView.LayoutParams(-1, dp(112)).apply { bottomMargin = dp(8) } }
        val image = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)).apply { rightMargin = dp(10) } }
        val info = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(parent.context).apply { textSize = 12f; setTextColor(primaryText); setTypeface(null, Typeface.BOLD) }
        val details = TextView(parent.context).apply { textSize = 8.5f; setTextColor(muted); setPadding(0, dp(5), 0, 0) }
        info.addView(title); info.addView(details); box.addView(image); box.addView(info, LinearLayout.LayoutParams(0, -1, 1f))
        return ResultHolder(box, image, title, details)
    }

    private data class VisualRow(val image: ImageEntity, val percent: Int, val band: String)
    private class ResultHolder(view: ViewGroup, val image: ImageView, val title: TextView, val details: TextView) : RecyclerView.ViewHolder(view)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() { semanticService?.close(); semanticService = null; super.onDestroy() }
}
