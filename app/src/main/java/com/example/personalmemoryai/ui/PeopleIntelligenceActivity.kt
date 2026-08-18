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
    private val bg = Color.rgb(7, 14, 22)
    private val cardBg = Color.rgb(15, 28, 41)
    private val text = Color.rgb(235, 246, 255)
    private val muted = Color.rgb(124, 160, 185)
    private val accent = Color.rgb(43, 145, 210)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 24)
            setBackgroundColor(bg)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        lifecycleScope.launch {
            val db = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext) }
            val people = withContext(Dispatchers.IO) { db.personDao().getMostObserved() }
            val totalFaces = withContext(Dispatchers.IO) { db.faceDao().count() }
            val embeddings = withContext(Dispatchers.IO) { db.faceDao().countWithEmbeddings() }
            val matchable = withContext(Dispatchers.IO) { db.faceDao().countMatchable() }

            root.addView(label("FACE INTELLIGENCE / PEOPLE", 22f, text, true))
            root.addView(label("IDENTITY CLUSTERS  •  VISUAL SUBJECT ANALYSIS  •  LOCAL ONLY", 10f, muted, false))
            root.addView(space(12))

            val overview = LinearLayout(this@PeopleIntelligenceActivity).apply { orientation = LinearLayout.HORIZONTAL }
            metric(overview, "SUBJECTS", people.size.toString())
            metric(overview, "FACES", totalFaces.toString())
            metric(overview, "EMBEDDINGS", embeddings.toString())
            metric(overview, "MATCHABLE", matchable.toString())
            root.addView(overview, margin())

            root.addView(label("SUBJECT CLUSTERS", 13f, text, true), margin())
            if (people.isEmpty()) {
                root.addView(label("لا توجد مجموعات وجوه بعد. شغّل BUILD FACE INTELLIGENCE INDEX.", 13f, muted, false))
                return@launch
            }

            people.forEachIndexed { index, person ->
                val name = person.displayName?.takeIf { it.isNotBlank() }
                    ?: "UNKNOWN SUBJECT ${String.format(Locale.US, "%03d", index + 1)}"
                val faces = withContext(Dispatchers.IO) { db.faceDao().getByPersonId(person.id) }
                val representative = faces.maxByOrNull { it.qualityScore }
                val card = LinearLayout(this@PeopleIntelligenceActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(15, 14, 15, 14)
                    setBackgroundColor(cardBg)
                    isClickable = true
                    setOnClickListener {
                        startActivity(Intent(this@PeopleIntelligenceActivity, PersonProfileActivity::class.java).apply {
                            putExtra("person_id", person.id)
                        })
                    }
                }
                card.addView(label(name, 18f, text, true))
                card.addView(label(
                    "SUBJECT ID  ${String.format(Locale.US, "%06d", person.id)}\n" +
                    "OBSERVATIONS  ${faces.size}   •   BEST QUALITY  ${"%.2f".format(Locale.US, person.bestQualityScore)}\n" +
                    "EMBEDDING  ${if (person.hasRepresentativeEmbedding) "READY" else "MISSING"}   •   MATCHABLE  ${faces.count { it.usableForMatching }}\n" +
                    "REPRESENTATIVE  ${if (representative != null) "Q ${"%.2f".format(Locale.US, representative.qualityScore)}" else "NONE"}",
                    10f, muted, false
                ))
                card.addView(label("OPEN SUBJECT PROFILE  ›", 10f, accent, true))
                root.addView(card, margin())
            }
        }
    }

    private fun metric(parent: LinearLayout, title: String, value: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 9, 8, 9)
            setBackgroundColor(cardBg)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.setMargins(3, 0, 3, 0) }
        }
        box.addView(label(title, 7.5f, muted, true))
        box.addView(label(value, 15f, text, true))
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
