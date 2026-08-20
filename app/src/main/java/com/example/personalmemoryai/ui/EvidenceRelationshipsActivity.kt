package com.example.personalmemoryai.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.database.PersonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Evidence relationship workspace derived from persisted Room relations.
 *
 * Global mode: shows the most observed Subjects and acts as the relationship index.
 * Subject mode: derives Person -> Face -> Image -> Object/OCR -> Related Subject links.
 * No new relationship schema and no live AI inference are required. TextEncoder is not used.
 */
class EvidenceRelationshipsActivity : AppCompatActivity() {
    private val primaryText = Color.rgb(233, 255, 244)
    private val muted = Color.rgb(127, 169, 154)
    private val green = Color.rgb(57, 255, 136)
    private val cyan = Color.rgb(53, 232, 255)
    private val violet = Color.rgb(179, 107, 255)
    private val amber = Color.rgb(255, 210, 63)
    private val red = Color.rgb(255, 48, 79)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val personId = intent.getLongExtra("person_id", -1L)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(24)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intelligence) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)

        lifecycleScope.launch {
            val graph = loadGraph(personId) ?: run { finish(); return@launch }
            if (graph.person == null) renderGlobal(root, graph) else renderSubject(root, graph)
        }
    }

    private suspend fun loadGraph(personId: Long): Graph? = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(applicationContext)
        if (personId <= 0L) {
            val subjects = db.personDao().getMostObserved().take(20)
            return@withContext Graph(null, 0, emptyList(), emptyMap(), emptyList(), emptyList(), subjects)
        }

        val person = db.personDao().getById(personId) ?: return@withContext null
        val faces = db.faceDao().getByPersonId(personId)
        val images = faces.mapNotNull { db.imageDao().getById(it.imageId) }.distinctBy { it.id }.sortedByDescending { it.dateTaken ?: it.dateModified ?: it.indexedAt }
        val relatedCounts = mutableMapOf<Long, Int>()
        val relatedPeopleCache = mutableMapOf<Long, PersonEntity>()
        val objectEvidence = mutableMapOf<Long, List<com.example.personalmemoryai.database.ObjectEntity>>()
        val imageFaces = mutableMapOf<Long, Int>()

        for (image in images) {
            val imageFacesList = db.faceDao().getByImageId(image.id)
            imageFaces[image.id] = imageFacesList.size
            imageFacesList.mapNotNull { it.personId }.filter { it != personId }.distinct().forEach { otherId -> relatedCounts[otherId] = (relatedCounts[otherId] ?: 0) + 1 }
            objectEvidence[image.id] = db.objectDao().findForImage(image.id)
        }
        if (relatedCounts.isNotEmpty()) db.personDao().getByIds(relatedCounts.keys.toList()).forEach { relatedPeopleCache[it.id] = it }

        Graph(
            person = person,
            faces = faces.size,
            images = images,
            imageFaces = imageFaces,
            relatedPeople = relatedCounts.entries.sortedByDescending { it.value }.mapNotNull { (id, sharedImages) -> relatedPeopleCache[id]?.let { RelatedPerson(it, sharedImages) } },
            objects = objectEvidence.values.flatten().groupBy { it.label }.mapValues { (_, values) -> values.size }.entries.sortedByDescending { it.value }.take(12),
            globalSubjects = emptyList()
        )
    }

    private fun renderGlobal(root: LinearLayout, graph: Graph) {
        root.addView(globalHeader(graph.globalSubjects.size))
        root.addView(section("SUBJECT RELATIONSHIP INDEX", cyan), margin())
        root.addView(message("SELECT A SUBJECT", "اختر Subject لفتح شبكة الأدلة الخاصة به: الصور، الوجوه، الكائنات، OCR وSubjects المرتبطة.", false), margin())
        if (graph.globalSubjects.isEmpty()) { root.addView(message("NO SUBJECT CLUSTERS", "لا توجد Subject clusters محفوظة بعد.", false), margin()); return }
        graph.globalSubjects.forEach { root.addView(globalSubjectCard(it), margin()) }
    }

    private fun renderSubject(root: LinearLayout, graph: Graph) {
        val person = graph.person ?: return
        root.addView(header(person, graph.images.size, graph.faces))
        root.addView(section("RELATIONSHIP OVERVIEW", cyan), margin())
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metric(metrics, "EVIDENCE", graph.images.size.toString(), green)
        metric(metrics, "FACE NODES", graph.faces.toString(), cyan)
        metric(metrics, "LINKED SUBJECTS", graph.relatedPeople.size.toString(), violet)
        metric(metrics, "OBJECT TYPES", graph.objects.size.toString(), amber)
        root.addView(metrics, margin())
        root.addView(section("EVIDENCE NETWORK", violet), margin())
        root.addView(networkPanel(graph), margin())
        root.addView(section("RELATED SUBJECTS / SHARED EVIDENCE", green), margin())
        if (graph.relatedPeople.isEmpty()) root.addView(message("NO LINKED SUBJECTS", "لم يتم العثور على Subject آخر مرتبط بنفس الأدلة المحفوظة.", false), margin()) else graph.relatedPeople.forEach { root.addView(relatedPersonCard(it), margin()) }
        root.addView(section("OBJECT RELATIONSHIPS", amber), margin())
        if (graph.objects.isEmpty()) root.addView(message("NO OBJECT EVIDENCE", "لا توجد كائنات محفوظة ضمن الصور المرتبطة بهذا Subject.", false), margin()) else graph.objects.forEach { root.addView(objectCard(it.key, it.value), margin()) }
        root.addView(section("SOURCE EVIDENCE LINKS", cyan), margin())
        graph.images.take(30).forEachIndexed { index, image -> root.addView(evidenceLinkCard(index + 1, image, graph.imageFaces[image.id] ?: 0), margin()) }
    }

    private fun globalHeader(total: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(5).toFloat()
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "◈ EVIDENCE RELATIONSHIPS / GLOBAL INDEX"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "RELATIONSHIP COMMAND"; textSize = 26f; setTextColor(this@EvidenceRelationshipsActivity.primaryText); setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, dp(2)) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "$total ACTIVE SUBJECT CLUSTERS  •  LOCAL GRAPH  •  ROOM INDEX"; textSize = 9f; setTextColor(green) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "NO LIVE INFERENCE  •  DERIVED FROM PERSISTED PERSON / FACE / IMAGE EVIDENCE"; textSize = 8f; setTextColor(muted); setPadding(0, dp(6), 0, 0) })
    }

    private fun globalSubjectCard(person: PersonEntity) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
        setOnClickListener { startActivity(Intent(this@EvidenceRelationshipsActivity, EvidenceRelationshipsActivity::class.java).apply { putExtra("person_id", person.id) }) }
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "◎ SUBJECT ${String.format(Locale.US, "%06d", person.id)}  /  ${person.displayName?.takeIf { it.isNotBlank() } ?: "UNKNOWN SUBJECT"}"; textSize = 12f; setTextColor(green); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "${person.faceCount} FACE OBSERVATIONS  •  QUALITY ${String.format(Locale.US, "%.2f", person.bestQualityScore)}  •  ${if (person.hasRepresentativeEmbedding) "REPRESENTATIVE READY" else "REPRESENTATIVE MISSING"}"; textSize = 8.5f; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
    }

    private fun header(person: PersonEntity, images: Int, faces: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); elevation = dp(5).toFloat()
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "◈ EVIDENCE RELATIONSHIPS / LOCAL GRAPH"; textSize = 10f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = person.displayName?.takeIf { it.isNotBlank() } ?: "UNKNOWN SUBJECT"; textSize = 26f; setTextColor(this@EvidenceRelationshipsActivity.primaryText); setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, dp(2)) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "SUBJECT ${String.format(Locale.US, "%06d", person.id)}  •  ${images} IMAGES  •  ${faces} FACES"; textSize = 9f; setTextColor(green) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "DERIVED GRAPH  •  PERSON → FACE → IMAGE → OBJECT / OCR → RELATED SUBJECT"; textSize = 8f; setTextColor(muted); setPadding(0, dp(6), 0, 0) })
    }

    private fun networkPanel(graph: Graph): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel)
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "[SUBJECT ${String.format(Locale.US, "%06d", graph.person!!.id)}]"; textSize = 11f; setTextColor(green); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "      │\n   ┌──┴──┐\n FACES  IMAGES\n   │      │\n   └──┬───┘\n      │\n OBJECTS • OCR • RELATED SUBJECTS"; textSize = 10f; setTextColor(muted); typeface = Typeface.MONOSPACE; setPadding(0, dp(7), 0, dp(7)) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "${graph.relatedPeople.size} linked subjects  •  ${graph.objects.size} object types  •  ${graph.images.size} source images"; textSize = 8.5f; setTextColor(cyan) })
    }

    private fun relatedPersonCard(related: RelatedPerson): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel)
        setOnClickListener { startActivity(Intent(this@EvidenceRelationshipsActivity, PersonProfileActivity::class.java).apply { putExtra("person_id", related.person.id) }) }
        val name = related.person.displayName?.takeIf { it.isNotBlank() } ?: "UNKNOWN SUBJECT"
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "◉ SUBJECT ${String.format(Locale.US, "%06d", related.person.id)}  /  $name"; textSize = 12f; setTextColor(green); setTypeface(null, Typeface.BOLD) })
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "SHARED EVIDENCE  ${related.sharedImages} IMAGES  •  ${related.person.faceCount} TOTAL OBSERVATIONS  •  QUALITY ${String.format(Locale.US, "%.2f", related.person.bestQualityScore)}"; textSize = 8.5f; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
    }

    private fun objectCard(label: String, count: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(11), dp(9), dp(11), dp(9)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel)
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "◆  $label"; textSize = 11f; setTextColor(this@EvidenceRelationshipsActivity.primaryText); setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "$count OBS"; textSize = 9f; setTextColor(amber); setTypeface(null, Typeface.BOLD) })
    }

    private fun evidenceLinkCard(sequence: Int, image: ImageEntity, faceCount: Int): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_neon_panel); setOnClickListener { ImageViewerActivity.start(this@EvidenceRelationshipsActivity, image.uri) } }
        val thumb = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply { rightMargin = dp(10) }; scaleType = ImageView.ScaleType.CENTER_CROP; try { setImageURI(Uri.parse(image.uri)) } catch (_: Exception) {} }
        card.addView(thumb)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        info.addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "EVIDENCE ${String.format(Locale.US, "%03d", sequence)}  •  ${formatTime(image.dateTaken ?: image.dateModified)}"; textSize = 8f; setTextColor(cyan); setTypeface(null, Typeface.BOLD) })
        info.addView(TextView(this@EvidenceRelationshipsActivity).apply { text = image.fileName; textSize = 12f; setTextColor(this@EvidenceRelationshipsActivity.primaryText); setTypeface(null, Typeface.BOLD); setPadding(0, dp(5), 0, dp(4)); maxLines = 2 })
        info.addView(TextView(this@EvidenceRelationshipsActivity).apply { text = "FACE $faceCount  •  OCR ${if (image.ocrText.isBlank()) "NONE" else "YES"}  •  OBJECTS ${if (image.detectedObjects.isBlank() || image.detectedObjects == "[]") "NONE" else "YES"}"; textSize = 8f; setTextColor(green) })
        card.addView(info)
        return card
    }

    private fun metric(parent: LinearLayout, title: String, value: String, color: Int) {
        parent.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(9), dp(9), dp(8)); setBackgroundResource(com.example.personalmemoryai.R.drawable.bg_intel_panel); layoutParams = LinearLayout.LayoutParams(0, dp(78), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }; addView(TextView(this@EvidenceRelationshipsActivity).apply { text = title; textSize = 7.5f; setTextColor(muted); setTypeface(null, Typeface.BOLD) }); addView(TextView(this@EvidenceRelationshipsActivity).apply { text = value; textSize = 17f; setTextColor(color); setTypeface(null, Typeface.BOLD); setPadding(0, dp(4), 0, 0) }) })
    }

    private fun section(value: String, color: Int) = TextView(this).apply { text = "▌  $value"; textSize = 10f; setTextColor(color); setTypeface(null, Typeface.BOLD); setPadding(dp(3), dp(6), dp(3), dp(7)) }
    private fun message(title: String, body: String, critical: Boolean) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundResource(if (critical) com.example.personalmemoryai.R.drawable.bg_critical_alert else com.example.personalmemoryai.R.drawable.bg_neon_panel); addView(TextView(this@EvidenceRelationshipsActivity).apply { text = title; textSize = 13f; setTextColor(if (critical) red else this@EvidenceRelationshipsActivity.primaryText); setTypeface(null, Typeface.BOLD) }); addView(TextView(this@EvidenceRelationshipsActivity).apply { text = body; textSize = 10f; setTextColor(muted); setPadding(0, dp(5), 0, 0) }) }
    private fun formatTime(time: Long?): String = time?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it)) } ?: "UNKNOWN"
    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(9)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private data class RelatedPerson(val person: PersonEntity, val sharedImages: Int)
    private data class Graph(
        val person: PersonEntity?,
        val faces: Int,
        val images: List<ImageEntity>,
        val imageFaces: Map<Long, Int>,
        val relatedPeople: List<RelatedPerson>,
        val objects: List<Map.Entry<String, Int>>,
        val globalSubjects: List<PersonEntity>
    )
}
