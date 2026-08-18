package com.example.personalmemoryai

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.indexing.ImageIndexer
import com.example.personalmemoryai.semantic.SemanticSearchService
import com.example.personalmemoryai.ui.ImageResultAdapter
import com.example.personalmemoryai.ui.ImageViewerActivity
import com.example.personalmemoryai.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AppDatabase
    private lateinit var adapter: ImageResultAdapter
    private lateinit var semanticSearchService: SemanticSearchService
    private var indexer: ImageIndexer? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            showStatus("لم يتم اختيار أي صورة")
            return@registerForActivityResult
        }
        val limitedUris = uris.take(100)
        showStatus("تم اختيار ${limitedUris.size} صورة\nبدء الفهرسة...")
        indexImages(limitedUris)
    }

    private val semanticImagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            showStatus("لم يتم اختيار صورة للبحث الدلالي")
            return@registerForActivityResult
        }
        semanticSearch(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(applicationContext)
        indexer = ImageIndexer(applicationContext)
        semanticSearchService = SemanticSearchService(applicationContext)

        setupRecyclerView()
        binding.selectButton.setOnClickListener { imagePicker.launch("image/*") }
        binding.searchButton.setOnClickListener { performSearch(binding.searchEditText.text.toString()) }
        binding.semanticSearchButton.setOnClickListener { semanticImagePicker.launch("image/*") }

        showStatus("Personal Memory AI\n\nجاهز لفهرسة الصور والبحث بالنص والكائنات والبحث الدلالي بالصور.")
        loadAllImages()
    }

    private fun setupRecyclerView() {
        adapter = ImageResultAdapter { image ->
            ImageViewerActivity.start(this, image.uri)
        }
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultsRecyclerView.adapter = adapter
    }

    private fun indexImages(uris: List<Uri>) {
        lifecycleScope.launch {
            binding.progressBar.visibility = android.view.View.VISIBLE
            binding.progressBar.max = uris.size
            binding.progressBar.progress = 0
            var completed = 0
            var failed = 0

            for (uri in uris) {
                try {
                    val entity = withContext(Dispatchers.IO) { indexer?.indexImage(uri) }
                    if (entity != null) completed++ else failed++
                } catch (t: Throwable) {
                    failed++
                    t.printStackTrace()
                }
                val processed = completed + failed
                binding.progressBar.progress = processed
                binding.counterText.text = "$processed / ${uris.size}"
                binding.statusText.text =
                    "فهرسة الصورة $processed من ${uris.size}\nنجح: $completed | فشل: $failed"
            }

            binding.progressBar.visibility = android.view.View.GONE
            loadAllImages()
            binding.statusText.text =
                "اكتملت الفهرسة\n\nتمت معالجة: ${uris.size}\nنجح: $completed\nفشل: $failed"
        }
    }

    /**
     * Until Text Encoder is added, textual search is keyword-based over OCR and
     * YOLO object labels. Multi-word queries are split so a query such as
     * "رجل في غرفة وبجانبه كلب" can match images containing person + dog even
     * though the exact sentence is not stored anywhere.
     */
    private fun performSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            showStatus("اكتب شيئًا للبحث بالنص أو الكائنات.")
            return
        }

        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val exact = database.imageDao().searchTextAndObjects(normalized)
                    val tokens = normalized
                        .lowercase(Locale.getDefault())
                        .split(Regex("[^\\p{L}\\p{Nd}]+"))
                        .map { it.trim() }
                        .filter { it.length >= 2 }
                        .filterNot { it in STOP_WORDS }
                        .distinct()

                    val scores = LinkedHashMap<Long, Pair<ImageEntity, Int>>()
                    for (token in tokens) {
                        for (image in database.imageDao().searchTextAndObjects(token)) {
                            val previous = scores[image.id]
                            scores[image.id] = image to ((previous?.second ?: 0) + 1)
                        }
                    }
                    for (image in exact) {
                        val previous = scores[image.id]
                        scores[image.id] = image to ((previous?.second ?: 0) + 2)
                    }

                    scores.values
                        .sortedWith(compareByDescending<Pair<ImageEntity, Int>> { it.second }
                            .thenByDescending { it.first.dateTaken ?: 0L })
                        .map { it.first }
                        .ifEmpty { exact }
                }

                adapter.submitList(results)
                binding.counterText.text = "النتائج: ${results.size}"
                binding.statusText.text = if (results.isEmpty()) {
                    "لم أجد صورًا تطابق كلمات البحث:\n$normalized"
                } else {
                    "تم العثور على ${results.size} صورة مطابقة للكلمات والكائنات.\n" +
                        "البحث الدلالي النصي عبر Text Encoder مؤجل حاليًا."
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showStatus("حدث خطأ أثناء البحث: ${e.message}")
                Toast.makeText(this@MainActivity, "خطأ في البحث: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun semanticSearch(queryUri: Uri) {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = android.view.View.VISIBLE
                binding.statusText.text = "جاري تجهيز MobileCLIP-S2 والبحث الدلالي..."
                val results = withContext(Dispatchers.IO) {
                    semanticSearchService.ensureModel()
                    semanticSearchService.searchSimilarImages(queryUri, limit = 30)
                }
                adapter.submitList(results.map { it.image })
                binding.counterText.text = "النتائج الدلالية: ${results.size}"
                binding.statusText.text = if (results.isEmpty()) {
                    "لا توجد صور مفهرسة دلاليًا بعد. فهرس الصور أولًا."
                } else {
                    "تم ترتيب ${results.size} صورة حسب التشابه البصري."
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showStatus("تعذر البحث الدلالي: ${e.message}")
                Toast.makeText(this@MainActivity, "تعذر تشغيل MobileCLIP-S2: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun loadAllImages() {
        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) { database.imageDao().getAll() }
            adapter.submitList(images)
            binding.counterText.text = "الفهرس: ${images.size} صورة"
        }
    }

    private fun showStatus(text: String) {
        binding.statusText.text = text
    }

    override fun onDestroy() {
        semanticSearchService.close()
        indexer?.close()
        super.onDestroy()
    }

    companion object {
        private val STOP_WORDS = setOf(
            "في", "من", "على", "عن", "إلى", "الى", "مع", "و", "أو", "او", "ثم", "هذا", "هذه",
            "ذلك", "تلك", "بجانب", "بجانبه", "بجانبها", "هناك", "the", "a", "an", "in", "on", "of",
            "with", "and", "or", "near", "next", "to"
        )
    }
}
