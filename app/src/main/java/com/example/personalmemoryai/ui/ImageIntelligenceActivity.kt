package com.example.personalmemoryai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.personalmemoryai.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ImageIntelligenceActivity : AppCompatActivity() {
    private val primaryText = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(14)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence) }
        root.addView(header())
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(18)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val data = withContext(Dispatchers.IO) {
                val images = db.imageDao().getAll(); val imageEmbeddings = db.embeddingDao().countByOwnerType("IMAGE"); val ocr = db.imageDao().countWithOcr(); val objects = db.imageDao().countWithDetectedObjects(); Quad(images, imageEmbeddings, ocr, objects)
            }
            content.addView(telemetry(data), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) })
            content.addView(sectionTitle("INDEXED VISUAL EVIDENCE", cyan))
            val list = RecyclerView(this@ImageIntelligenceActivity).apply { layoutManager = LinearLayoutManager(this@ImageIntelligenceActivity); isNestedScrollingEnabled = false }
            content.addView(list, LinearLayout.LayoutParams(-1, -2)); list.adapter = EvidenceAdapter(data.images) { image -> ImageViewerActivity.start(this@ImageIntelligenceActivity, image.uri) }
        }
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(5).toFloat()
        addView(TextView(this@ImageIntelligenceActivity).apply { text = "◈ VISUAL INTELLIGENCE / EVIDENCE"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@ImageIntelligenceActivity).apply { text = "IMAGE INTELLIGENCE"; textSize = 27f; setTextColor(primaryText); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(2)) })
        addView(TextView(this@ImageIntelligenceActivity).apply { text = "OCR  •  OBJECTS  •  VISUAL EMBEDDINGS  •  SOURCE EVIDENCE"; textSize = 9f; setTextColor(muted) })
    }
    private fun telemetry(data: Quad) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        listOf("IMAGES" to data.images.size.toString(), "VISUAL EMB" to data.imageEmbeddings.toString(), "OCR" to data.ocr.toString(), "OBJECTS" to data.objects.toString()).forEachIndexed { index, pair ->
            val box = LinearLayout(this@ImageIntelligenceActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(9), dp(10), dp(9)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel); layoutParams = LinearLayout.LayoutParams(0, dp(78), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                addView(TextView(this@ImageIntelligenceActivity).apply { text = pair.first; textSize = 7.5f; setTextColor(muted); setTypeface(null, Typeface.BOLD) })
                addView(TextView(this@ImageIntelligenceActivity).apply { text = pair.second; textSize = 18f; setTextColor(if (index == 1) cyan else neon); setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, 0) })
            }; addView(box)
        }
    }
    private fun sectionTitle(value: String, color: Int) = TextView(this).apply { text = "▌  $value"; textSize = 10f; setTextColor(color); setTypeface(null, Typeface.BOLD); setPadding(dp(3), dp(5), dp(3), dp(7)) }

    private class EvidenceAdapter(private val items: List<com.example.personalmemoryai.database.ImageEntity>, private val click: (com.example.personalmemoryai.database.ImageEntity) -> Unit) : RecyclerView.Adapter<EvidenceAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val box = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(parent.context, 10), dp(parent.context, 10), dp(parent.context, 10), dp(parent.context, 10)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(parent.context, 9) } }
            val image = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(-1, dp(parent.context, 175)) }
            val title = TextView(parent.context).apply { textSize = 15f; setTextColor(Color.rgb(235,246,255)); setTypeface(null, Typeface.BOLD); setPadding(0, dp(parent.context, 8), 0, dp(parent.context, 3)) }
            val details = TextView(parent.context).apply { textSize = 9.5f; setTextColor(Color.rgb(126,157,178)) }
            box.addView(image); box.addView(title); box.addView(details); return Holder(box, image, title, details)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) { val image = items[position]; holder.image.setImageURI(android.net.Uri.parse(image.uri)); holder.title.text = "${position + 1}. ${image.fileName}"; holder.details.text = "OCR  ${if (image.ocrText.isBlank()) "NONE" else "AVAILABLE"}  •  QUALITY  ${String.format(Locale.US, "%.2f", image.ocrQualityScore)}\nOBJECTS  ${if (image.detectedObjects.isBlank() || image.detectedObjects == "[]") "NONE" else "INDEXED"}\n${image.width}×${image.height}  •  ${image.mimeType ?: "IMAGE"}\nPATH  ${image.filePath ?: image.uri}"; holder.itemView.setOnClickListener { click(image) } }
        override fun getItemCount(): Int = items.size
        class Holder(view: ViewGroup, val image: ImageView, val title: TextView, val details: TextView) : RecyclerView.ViewHolder(view)
        companion object { fun dp(context: android.content.Context, value: Int) = (value * context.resources.displayMetrics.density).toInt() }
    }
    private data class Quad(val images: List<com.example.personalmemoryai.database.ImageEntity>, val imageEmbeddings: Long, val ocr: Long, val objects: Long)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
