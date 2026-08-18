package com.example.personalmemoryai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PersonProfileActivity : AppCompatActivity() {
    private val bg = Color.rgb(7, 14, 22)
    private val panel = Color.rgb(15, 27, 39)
    private val text = Color.rgb(232, 244, 252)
    private val muted = Color.rgb(132, 166, 190)
    private val accent = Color.rgb(47, 148, 211)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val personId = intent.getLongExtra("person_id", -1L)
        if (personId <= 0L) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 24)
            setBackgroundColor(bg)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        lifecycleScope.launch {
            val db = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext) }
            val person = withContext(Dispatchers.IO) { db.personDao().getById(personId) } ?: run { finish(); return@launch }
            val faces = withContext(Dispatchers.IO) { db.faceDao().getByPersonId(personId) }
            val images = withContext(Dispatchers.IO) {
                faces.mapNotNull { db.imageDao().getById(it.imageId) }.distinctBy { it.id }
            }

            root.addView(label("FACE INTELLIGENCE / SUBJECT PROFILE", 22f, text, true))
            root.addView(label("SUBJECT ${String.format(Locale.US, "%03d", person.id)}  •  LOCAL VISUAL CLUSTER", 10f, muted, false))
            root.addView(space(12))

            val identity = panel()
            identity.addView(label(person.displayName?.takeIf { it.isNotBlank() } ?: "UNKNOWN SUBJECT", 21f, text, true))
            identity.addView(label(person.description?.takeIf { it.isNotBlank() } ?: "No analyst description assigned.", 11f, muted, false))
            identity.addView(label("STATUS     ${if (person.isFavorite) "PRIORITY SUBJECT" else "UNCLASSIFIED CLUSTER"}\nMODEL      ${person.modelVersion}\nUPDATED    ${person.updatedAt}", 11f, muted, false))
            root.addView(identity, margin())

            val metrics = LinearLayout(this@PersonProfileActivity).apply { orientation = LinearLayout.HORIZONTAL }
            metric(metrics, "OBSERVATIONS", faces.size.toString())
            metric(metrics, "BEST QUALITY", String.format(Locale.US, "%.2f", person.bestQualityScore))
            metric(metrics, "EMBEDDING", if (person.hasRepresentativeEmbedding) "READY" else "MISSING")
            root.addView(metrics, margin())

            root.addView(label("REPRESENTATIVE EVIDENCE", 13f, text, true), margin())
            val representative = faces.maxByOrNull { it.qualityScore }
            val repImage = representative?.let { face -> images.firstOrNull { it.id == face.imageId } }
            if (repImage != null) root.addView(imageCard(repImage.uri, "BEST FACE  •  QUALITY ${String.format(Locale.US, "%.2f", representative!!.qualityScore)}"), margin())
            else root.addView(label("No representative image available.", 11f, muted, false), margin())

            root.addView(label("ASSOCIATED EVIDENCE / ${images.size} IMAGES", 13f, text, true), margin())
            images.forEach { image -> root.addView(imageCard(image.uri, image.fileName), margin()) }
        }
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14, 14, 14, 14)
        setBackgroundColor(panel)
    }

    private fun imageCard(uri: String, caption: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(panel)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(21, 35, 48))
            layoutParams = LinearLayout.LayoutParams(-1, 360)
            try { setImageURI(Uri.parse(uri)) } catch (_: Exception) { }
        }
        box.addView(image)
        box.addView(label(caption, 10f, muted, true))
        return box
    }

    private fun metric(parent: LinearLayout, title: String, value: String) {
        val box = panel().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(3, 0, 3, 0) } }
        box.addView(label(title, 8f, muted, true))
        box.addView(label(value, 16f, text, true))
        parent.addView(box)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 3, 0, 3)
    }

    private fun space(px: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, px) }
    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 10) }
}
