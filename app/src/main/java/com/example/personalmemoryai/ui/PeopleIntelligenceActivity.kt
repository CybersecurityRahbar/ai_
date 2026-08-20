package com.example.personalmemoryai.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PeopleIntelligenceActivity : AppCompatActivity() {
    private val bg = Color.rgb(5, 11, 17)
    private val text = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(126, 157, 178)
    private val neon = Color.rgb(151, 255, 0)
    private val cyan = Color.rgb(89, 226, 255)
    private val violet = Color.rgb(179, 107, 255)
    private val red = Color.rgb(255, 48, 79)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(18)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence) }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)

        lifecycleScope.launch {
            val db = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext) }
            val people = withContext(Dispatchers.IO) { db.personDao().getMostObserved() }
            val totalFaces = withContext(Dispatchers.IO) { db.faceDao().count() }
            val embeddings = withContext(Dispatchers.IO) { db.faceDao().countWithEmbeddings() }
            val matchable = withContext(Dispatchers.IO) { db.faceDao().countMatchable() }

            root.addView(header())
            root.addView(sectionTitle("IDENTITY INDEX TELEMETRY", cyan))
            val metrics = LinearLayout(this@PeopleIntelligenceActivity).apply { orientation = LinearLayout.HORIZONTAL }
            metric(metrics, "SUBJECTS", people.size.toString(), violet)
            metric(metrics, "FACES", totalFaces.toString(), neon)
            metric(metrics, "EMBEDDINGS", embeddings.toString(), cyan)
            metric(metrics, "MATCHABLE", matchable.toString(), neon)
            root.addView(metrics, margin())

            root.addView(sectionTitle("SUBJECT CLUSTERS / EVIDENCE", cyan), margin())
            if (people.isEmpty()) {
                root.addView(alert("NO SUBJECT CLUSTERS", "لم يتم إنشاء مجموعات وجوه بعد. لا يتم عرض هوية وهمية.", false), margin())
                return@launch
            }
            people.forEachIndexed { index, person ->
                val faces = withContext(Dispatchers.IO) { db.faceDao().getByPersonId(person.id) }
                val representative = faces.maxByOrNull { it.qualityScore }
                val name = person.displayName?.takeIf { it.isNotBlank() } ?: "UNKNOWN SUBJECT ${String.format(Locale.US, "%03d", index + 1)}"
                val card = LinearLayout(this@PeopleIntelligenceActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
                    elevation = dp(3).toFloat()
                    isClickable = true
                    setOnClickListener { startActivity(Intent(this@PeopleIntelligenceActivity, PersonProfileActivity::class.java).apply { putExtra("person_id", person.id) }) }
                }
                card.addView(TextView(this@PeopleIntelligenceActivity).apply { text = "${if (person.isFavorite) "◆ PRIORITY" else "◆ SUBJECT"}  /  ${name}"; textSize = 17f; setTextColor(if (person.isFavorite) neon else text); setTypeface(null, Typeface.BOLD) })
                card.addView(TextView(this@PeopleIntelligenceActivity).apply {
                    text = "SUBJECT ID  ${String.format(Locale.US, "%06d", person.id)}\nOBSERVATIONS  ${faces.size}  •  BEST QUALITY  ${"%.2f".format(Locale.US, person.bestQualityScore)}\nEMBEDDING  ${if (person.hasRepresentativeEmbedding) "READY" else "MISSING"}  •  MATCHABLE  ${faces.count { it.usableForMatching }}\nREPRESENTATIVE  ${if (representative != null) "AVAILABLE / Q ${"%.2f".format(Locale.US, representative.qualityScore)}" else "NONE"}"
                    textSize = 10f; setTextColor(muted); setPadding(0, dp(7), 0, dp(7)); typeface = Typeface.MONOSPACE
                })
                card.addView(TextView(this@PeopleIntelligenceActivity).apply { text = "OPEN SUBJECT PROFILE   ›"; textSize = 9f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
                root.addView(card, margin())
            }
        }
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(5).toFloat()
        addView(TextView(this@PeopleIntelligenceActivity).apply { text = "◈ HUMAN INTELLIGENCE / SUBJECTS"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@PeopleIntelligenceActivity).apply { text = "PEOPLE INTELLIGENCE"; textSize = 27f; setTextColor(text); setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(2)) })
        addView(TextView(this@PeopleIntelligenceActivity).apply { text = "FACE CLUSTERS  •  REPRESENTATIVES  •  QUALITY  •  MATCHABILITY"; textSize = 9f; setTextColor(muted) })
    }

    private fun sectionTitle(value: String, color: Int) = TextView(this).apply { text = "▌  $value"; textSize = 10f; setTextColor(color); setTypeface(null, Typeface.BOLD); setPadding(dp(3), dp(6), dp(3), dp(7)) }

    private fun metric(parent: LinearLayout, title: String, value: String, color: Int) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(9), dp(9), dp(8)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel); layoutParams = LinearLayout.LayoutParams(0, dp(78), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
            addView(TextView(this@PeopleIntelligenceActivity).apply { text = title; textSize = 7.5f; setTextColor(muted); setTypeface(null, Typeface.BOLD) })
            addView(TextView(this@PeopleIntelligenceActivity).apply { text = value; textSize = 18f; setTextColor(color); setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, 0) })
        })
    }

    private fun alert(title: String, message: String, critical: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundResource(if (critical) com.example.personalmemoryai.R.drawable.bg_critical_alert else com.example.personalmemoryai.R.drawable.bg_neon_panel)
        addView(TextView(this@PeopleIntelligenceActivity).apply { text = title; textSize = 13f; setTextColor(if (critical) red else text); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@PeopleIntelligenceActivity).apply { text = message; textSize = 10f; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
    }

    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(9)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
