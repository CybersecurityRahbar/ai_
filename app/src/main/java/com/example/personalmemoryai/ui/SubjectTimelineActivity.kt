package com.example.personalmemoryai.ui

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Subject-centric evidence timeline.
 *
 * This screen is deliberately presentation-only: it consumes indexed Room evidence and
 * does not rerun OCR, face analysis, object detection, or semantic inference.
 * Text Encoder is not involved in the current timeline.
 */
class SubjectTimelineActivity : AppCompatActivity() {
    private val text = Color.rgb(232, 244, 252)
    private val muted = Color.rgb(132, 166, 190)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val violet = Color.rgb(179, 107, 255)
    private val amber = Color.rgb(255, 200, 87)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val personId = intent.getLongExtra("person_id", -1L)
        if (personId <= 0L) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence)
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        lifecycleScope.launch {
            val db = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext) }
            val person = withContext(Dispatchers.IO) { db.personDao().getById(personId) }
                ?: run { finish(); return@launch }
            val faces = withContext(Dispatchers.IO) { db.faceDao().getByPersonId(personId) }
            val images = withContext(Dispatchers.IO) {
                faces.mapNotNull { db.imageDao().getById(it.imageId) }
                    .distinctBy { it.id }
                    .sortedByDescending { it.dateTaken ?: it.dateModified ?: 0L }
            }

            root.addView(header(person.displayName ?: "UNKNOWN SUBJECT", person.id, images.size, faces.size))
            root.addView(section("CHRONOLOGICAL EVIDENCE / ${images.size} OBSERVATIONS", cyan), margin())

            if (images.isEmpty()) {
                root.addView(message("NO TIMELINE EVIDENCE", "لا توجد صور مرتبطة بهذا الـSubject حتى الآن.", false), margin())
                return@launch
            }

            var previousDay: String? = null
            images.forEachIndexed { index, image ->
                val day = formatDay(image.dateTaken ?: image.dateModified)
                if (day != previousDay) {
                    root.addView(dayMarker(day), margin())
                    previousDay = day
                }
                root.addView(eventCard(index + 1, image, faces.count { it.imageId == image.id }), margin())
            }
        }
    }

    private fun header(name: String, id: Long, images: Int, faces: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
        elevation = dp(5).toFloat()
        addView(TextView(this@SubjectTimelineActivity).apply {
            text = "◈ SUBJECT TIMELINE / EVIDENCE CHRONOLOGY"
            textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD)
        })
        addView(TextView(this@SubjectTimelineActivity).apply {
            text = name.ifBlank { "UNKNOWN SUBJECT" }
            textSize = 26f; setTextColor(text); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        })
        addView(TextView(this@SubjectTimelineActivity).apply {
            text = "SUBJECT ${String.format(Locale.US, "%06d", id)}  •  ${images} IMAGES  •  ${faces} FACE OBSERVATIONS"
            textSize = 9f; setTextColor(neon)
        })
        addView(TextView(this@SubjectTimelineActivity).apply {
            text = "CHRONOLOGY SOURCE  ROOM INDEX  •  NO LIVE INFERENCE"
            textSize = 8f; setTextColor(muted); setPadding(0, dp(6), 0, 0)
        })
    }

    private fun dayMarker(day: String) = TextView(this).apply {
        text = "━━  $day  ━━━━━━━━━━━━━━━━━"
        textSize = 9f; setTextColor(amber); setTypeface(null, Typeface.BOLD)
        setPadding(dp(3), dp(5), dp(3), dp(5))
    }

    private fun eventCard(sequence: Int, image: ImageEntity, faceCount: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel)
            elevation = dp(2).toFloat()
            isClickable = true
            setOnClickListener {
                startActivity(android.content.Intent(this@SubjectTimelineActivity, ImageViewerActivity::class.java).apply {
                    putExtra("image_id", image.id)
                })
            }
        }
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(8, 17, 25))
            try { setImageURI(Uri.parse(image.uri)) } catch (_: Exception) {}
        }
        box.addView(thumb)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(1), dp(4), dp(1))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        info.addView(TextView(this).apply {
            text = "EVIDENCE ${String.format(Locale.US, "%03d", sequence)}  •  ${formatTime(image.dateTaken ?: image.dateModified)}"
            textSize = 8.5f; setTextColor(cyan); setTypeface(null, Typeface.BOLD)
        })
        info.addView(TextView(this).apply {
            text = image.fileName
            textSize = 13f; setTextColor(text); setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(4))
            maxLines = 2
        })
        info.addView(TextView(this).apply {
            text = "FACE  ${faceCount}   •   OCR  ${if (image.ocrText.isBlank()) "NONE" else "AVAILABLE"}   •   OBJECTS  ${objectState(image.detectedObjects)}"
            textSize = 8f; setTextColor(neon)
        })
        info.addView(TextView(this).apply {
            text = "INDEXED  ${formatTime(image.indexedAt)}  •  ${image.width}×${image.height}"
            textSize = 7.5f; setTextColor(muted); setPadding(0, dp(5), 0, 0)
        })
        box.addView(info)
        return box
    }

    private fun section(value: String, color: Int) = TextView(this).apply {
        text = "▌  $value"; textSize = 10f; setTextColor(color); setTypeface(null, Typeface.BOLD)
        setPadding(dp(3), dp(6), dp(3), dp(7))
    }

    private fun message(title: String, body: String, critical: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundResource(if (critical) com.example.personalmemoryai.R.drawable.bg_critical_alert else com.example.personalmemoryai.R.drawable.bg_neon_panel)
        addView(TextView(this@SubjectTimelineActivity).apply { text = title; textSize = 13f; setTextColor(if (critical) Color.rgb(255,48,79) else text); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@SubjectTimelineActivity).apply { text = body; textSize = 10f; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
    }

    private fun objectState(value: String): String = if (value.isBlank() || value == "[]" || value == "{}") "NONE" else "INDEXED"
    private fun formatDay(time: Long?): String = time?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) } ?: "DATE UNKNOWN"
    private fun formatTime(time: Long?): String = time?.let { SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(Date(it)) } ?: "UNKNOWN"
    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(9)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
