package com.example.personalmemoryai.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(8, 15, 23))
        }
        val title = TextView(this).apply {
            text = "FACE INTELLIGENCE / PEOPLE"
            textSize = 22f
            setTextColor(Color.rgb(235, 246, 255))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)
        val subtitle = TextView(this).apply {
            text = "IDENTITY CLUSTERS • FACE OBSERVATIONS • LOCAL ANALYSIS"
            textSize = 10f
            setTextColor(Color.rgb(103, 139, 164))
            setPadding(0, 5, 0, 14)
        }
        root.addView(subtitle)
        setContentView(root)

        lifecycleScope.launch {
            val people = withContext(Dispatchers.IO) { AppDatabase.getInstance(applicationContext).personDao().getMostObserved() }
            if (people.isEmpty()) {
                root.addView(TextView(this@PeopleIntelligenceActivity).apply {
                    text = "لا توجد مجموعات وجوه بعد. شغّل BUILD FACE INTELLIGENCE INDEX من الشاشة الرئيسية."
                    textSize = 14f
                    setTextColor(Color.rgb(175, 198, 215))
                    setPadding(0, 24, 0, 0)
                })
            } else {
                people.forEachIndexed { index, person ->
                    val card = LinearLayout(this@PeopleIntelligenceActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(14, 14, 14, 14)
                        setBackgroundColor(Color.rgb(16, 29, 42))
                    }
                    val name = person.displayName?.takeIf { it.isNotBlank() } ?: "UNKNOWN SUBJECT ${String.format(Locale.US, "%03d", index + 1)}"
                    card.addView(TextView(this@PeopleIntelligenceActivity).apply {
                        text = name
                        textSize = 17f
                        setTextColor(Color.rgb(231, 244, 255))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    card.addView(TextView(this@PeopleIntelligenceActivity).apply {
                        text = "OBSERVATIONS: ${person.faceCount}   •   QUALITY: ${"%.2f".format(Locale.US, person.bestQualityScore)}\nREPRESENTATIVE: ${if (person.hasRepresentativeEmbedding) "READY" else "NOT AVAILABLE"}\nSTATUS: ${if (person.isFavorite) "PRIORITY SUBJECT" else "UNCLASSIFIED CLUSTER"}"
                        textSize = 11f
                        setTextColor(Color.rgb(132, 169, 194))
                        setPadding(0, 7, 0, 0)
                    })
                    root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 9) })
                }
            }
        }
    }
}
