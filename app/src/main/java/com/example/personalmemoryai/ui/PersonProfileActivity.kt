package com.example.personalmemoryai.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
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
    private val text = Color.rgb(232, 244, 252)
    private val muted = Color.rgb(132, 166, 190)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val violet = Color.rgb(179, 107, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val personId = intent.getLongExtra("person_id", -1L)
        if (personId <= 0L) { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(20)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence) }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)

        lifecycleScope.launch {
            val db = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext) }
            val person = withContext(Dispatchers.IO) { db.personDao().getById(personId) } ?: run { finish(); return@launch }
            val faces = withContext(Dispatchers.IO) { db.faceDao().getByPersonId(personId) }
            val images = withContext(Dispatchers.IO) { faces.mapNotNull { db.imageDao().getById(it.imageId) }.distinctBy { it.id } }
            val representative = faces.maxByOrNull { it.qualityScore }
            val repImage = representative?.let { face -> images.firstOrNull { it.id == face.imageId } }

            root.addView(header(person))
            root.addView(workspaceButtons(personId), margin())
            root.addView(section("SUBJECT TELEMETRY", cyan))
            val metrics = LinearLayout(this@PersonProfileActivity).apply { orientation = LinearLayout.HORIZONTAL }
            metric(metrics, "OBSERVATIONS", faces.size.toString(), neon)
            metric(metrics, "BEST QUALITY", String.format(Locale.US, "%.2f", person.bestQualityScore), cyan)
            metric(metrics, "MATCHABLE", faces.count { it.usableForMatching }.toString(), neon)
            root.addView(metrics, margin())

            root.addView(section("REPRESENTATIVE EVIDENCE", cyan), margin())
            if (repImage != null) root.addView(imageCard(repImage.uri, "BEST FACE  •  QUALITY ${String.format(Locale.US, "%.2f", representative!!.qualityScore)}", neon), margin())
            else root.addView(message("NO REPRESENTATIVE", "لا توجد صورة ممثلة محفوظة لهذا الشخص.", false), margin())

            root.addView(section("ASSOCIATED SOURCE EVIDENCE / ${images.size} IMAGES", cyan), margin())
            images.forEachIndexed { index, image ->
                root.addView(imageCard(image.uri, "EVIDENCE ${String.format(Locale.US, "%02d", index + 1)}  •  ${image.fileName}\nOCR ${if (image.ocrText.isBlank()) "NONE" else "AVAILABLE"}  •  OBJECTS ${if (image.detectedObjects == "[]" || image.detectedObjects.isBlank()) "NONE" else "INDEXED"}", violet), margin())
            }
        }
    }

    private fun workspaceButtons(personId: Long): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(Button(this@PersonProfileActivity).apply {
            text = "◷  OPEN SUBJECT TIMELINE"
            textSize = 10f
            setTextColor(Color.rgb(5, 10, 13))
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_button)
            setOnClickListener {
                startActivity(Intent(this@PersonProfileActivity, SubjectTimelineActivity::class.java).apply { putExtra("person_id", personId) })
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
        addView(Button(this@PersonProfileActivity).apply {
            text = "◎  OPEN EVIDENCE RELATIONSHIPS"
            textSize = 10f
            setTextColor(Color.rgb(5, 10, 13))
            setTypeface(null, Typeface.BOLD)
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_button)
            setOnClickListener {
                startActivity(Intent(this@PersonProfileActivity, EvidenceRelationshipsActivity::class.java).apply { putExtra("person_id", personId) })
            }
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private fun header(person: com.example.personalmemoryai.database.PersonEntity) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(5).toFloat()
        addView(TextView(this@PersonProfileActivity).apply { text = "◈ SUBJECT PROFILE / IDENTITY EVIDENCE"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@PersonProfileActivity).apply { text = person.displayName?.takeIf { it.isNotBlank() } ?: "UNKNOWN SUBJECT"; textSize = 27f; setTextColor(this@PersonProfileActivity.text); setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, dp(2)) })
        addView(TextView(this@PersonProfileActivity).apply { text = "SUBJECT ${String.format(Locale.US, "%06d", person.id)}  •  ${if (person.isFavorite) "PRIORITY SUBJECT" else "UNCLASSIFIED CLUSTER"}"; textSize = 9f; setTextColor(if (person.isFavorite) neon else muted) })
        addView(TextView(this@PersonProfileActivity).apply { text = "MODEL  ${person.modelVersion}  •  REPRESENTATIVE  ${if (person.hasRepresentativeEmbedding) "READY" else "MISSING"}"; textSize = 9f; setTextColor(muted); setPadding(0, dp(6), 0, 0) })
    }

    private fun section(value: String, color: Int) = TextView(this).apply { text = "▌  $value"; textSize = 10f; setTextColor(color); setTypeface(null, Typeface.BOLD); setPadding(dp(3), dp(6), dp(3), dp(7)) }

    private fun metric(parent: LinearLayout, title: String, value: String, color: Int) {
        parent.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(9), dp(9), dp(8)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel); layoutParams = LinearLayout.LayoutParams(0, dp(78), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }; addView(TextView(this@PersonProfileActivity).apply { text = title; textSize = 7.5f; setTextColor(muted); setTypeface(null, Typeface.BOLD) }); addView(TextView(this@PersonProfileActivity).apply { text = value; textSize = 17f; setTextColor(color); setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, 0) }) })
    }

    private fun imageCard(uri: String, caption: String, accent: Int): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(2).toFloat() }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(8, 17, 25)); layoutParams = LinearLayout.LayoutParams(-1, dp(285)); try { setImageURI(Uri.parse(uri)) } catch (_: Exception) {} }
        box.addView(image)
        box.addView(TextView(this).apply { text = caption; textSize = 9.5f; setTextColor(accent); setTypeface(null, Typeface.BOLD); setPadding(dp(3), dp(8), dp(3), dp(3)) })
        return box
    }

    private fun message(title: String, body: String, critical: Boolean) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundResource(if (critical) com.example.personalmemoryai.R.drawable.bg_critical_alert else com.example.personalmemoryai.R.drawable.bg_neon_panel); addView(TextView(this@PersonProfileActivity).apply { text = title; textSize = 13f; setTextColor(if (critical) Color.rgb(255,48,79) else this@PersonProfileActivity.text); setTypeface(null, Typeface.BOLD) }); addView(TextView(this@PersonProfileActivity).apply { text = body; textSize = 10f; setTextColor(muted); setPadding(0, dp(5), 0, 0) }) }
    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(9)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
