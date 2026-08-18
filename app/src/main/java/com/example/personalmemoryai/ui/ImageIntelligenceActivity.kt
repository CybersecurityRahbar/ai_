package com.example.personalmemoryai.ui

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
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

class ImageIntelligenceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.rgb(8, 15, 23))
        }
        root.addView(TextView(this).apply {
            text = "IMAGE INTELLIGENCE / EVIDENCE"
            textSize = 22f
            setTextColor(Color.rgb(235, 246, 255))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "INDEXED MEDIA • OCR • OBJECTS • VISUAL EMBEDDINGS"
            textSize = 10f
            setTextColor(Color.rgb(103, 139, 164))
            setPadding(0, 5, 0, 12)
        })
        val list = RecyclerView(this)
        list.layoutManager = LinearLayoutManager(this)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext).imageDao().getAll() }
            list.adapter = EvidenceAdapter(images) { image -> ImageViewerActivity.start(this@ImageIntelligenceActivity, image.uri) }
        }
    }

    private class EvidenceAdapter(
        private val items: List<com.example.personalmemoryai.database.ImageEntity>,
        private val click: (com.example.personalmemoryai.database.ImageEntity) -> Unit
    ) : RecyclerView.Adapter<EvidenceAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(TextView(parent.context).apply {
            setPadding(14, 13, 14, 13)
            setTextColor(Color.rgb(221, 238, 249))
            textSize = 12f
            setBackgroundColor(Color.rgb(16, 29, 42))
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val image = items[position]
            holder.text.text = "${image.fileName}\nOCR: ${image.ocrText?.take(120)?.replace('\n', ' ') ?: "—"}\nOBJECTS: ${image.detectedObjects?.take(160) ?: "—"}\nSIZE: ${image.width}×${image.height}"
            holder.text.setOnClickListener { click(image) }
        }
        override fun getItemCount(): Int = items.size
        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
