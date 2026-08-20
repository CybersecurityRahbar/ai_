package com.example.personalmemoryai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.indexing.FaceIndexCoordinator
import com.example.personalmemoryai.indexing.FullIndexingCoordinator
import com.example.personalmemoryai.indexing.ImageIndexer
import com.example.personalmemoryai.semantic.SemanticSearchService
import com.example.personalmemoryai.ui.DataCenterActivity
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
    private lateinit var faceIndexCoordinator: FaceIndexCoordinator
    private lateinit var fullIndexingCoordinator: FullIndexingCoordinator
    private var indexer: ImageIndexer? = null

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            showStatus("لم يتم اختيار أي صورة")
            return@registerForActivityResult
        }
        val limitedUris = uris.take(1000)
        showStatus("تم اختيار ${limitedUris.size} صورة • بدء خط أنابيب الفهرسة الكامل محليًا...")
        indexImages(limitedUris)
    }

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            showStatus("لم يتم اختيار نموذج")
            return@registerForActivityResult
        }
        importMobileClipModel(uri)
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
        faceIndexCoordinator = FaceIndexCoordinator(applicationContext)
        fullIndexingCoordinator = FullIndexingCoordinator(applicationContext)

        setupRecyclerView()
        binding.selectButton.setOnClickListener { imagePicker.launch("image/*") }
        binding.importModelButton.setOnClickListener {
            modelPicker.launch(arrayOf("application/octet-stream", "application/tflite", "*/*"))
        }
        binding.dataCenterButton.setOnClickListener {
            startActivity(Intent(this, DataCenterActivity::class.java))
        }
        binding.buildVisualIndexButton.setOnClickListener { buildVisualIndex() }
        binding.buildFaceIndexButton.setOnClickListener { buildFaceIndex() }
        binding.searchButton.setOnClickListener { performSearch(binding.searchEditText.text.toString()) }
        binding.semanticSearchButton.setOnClickListener { semanticImagePicker.launch("image/*") }

        updateModelStatus()
        updateFaceStatus()
        loadAllImages()
    }

    private fun setupRecyclerView() {
        adapter = ImageResultAdapter { image -> ImageViewerActivity.start(this, image.uri) }
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultsRecyclerView.adapter = adapter
    }

    private fun importMobileClipModel(uri: Uri) {
        lifecycleScope.launch {
            try {
                setBusy(true)
                binding.progressBar.max = 100
                binding.progressBar.progress = 0
                binding.modelStatusText.text = "استيراد MobileCLIP-S2 FP16..."
                withContext(Dispatchers.IO) {
                    semanticSearchService.importModel(uri) { copied, total ->
                        val percent = if (total > 0) ((copied * 100L) / total).toInt().coerceIn(0, 100) else 0
                        runOnUiThread {
                            binding.progressBar.progress = percent
                            binding.counterText.text = if (total > 0) "استيراد النموذج: $percent%" else "تم استيراد ${(copied / (1024 * 1024))} MB"
                        }
                    }
                }
                setBusy(false)
                updateModelStatus()
                binding.statusText.text = "تم استيراد MobileCLIP-S2. سيستخدم تلقائيًا أثناء الفهرسة الكاملة للصور."
            } catch (e: Exception) {
                setBusy(false)
                binding.modelStatusText.text = "○ نموذج MobileCLIP غير متوفر"
                showStatus("فشل استيراد النموذج: ${e.message}")
                Toast.makeText(this@MainActivity, "فشل استيراد النموذج", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateModelStatus() {
        lifecycleScope.launch {
            val installed = semanticSearchService.isModelInstalled()
            val embeddings = withContext(Dispatchers.IO) { semanticSearchService.imageEmbeddingCount() }
            binding.modelStatusText.text = if (installed) {
                val mb = semanticSearchService.modelSizeBytes() / (1024 * 1024)
                "● MobileCLIP-S2 جاهز • ${mb} MB • بصمات الصور: $embeddings"
            } else {
                "○ MobileCLIP-S2 غير مستورد • الفهرسة الأساسية ستعمل، والفهرسة البصرية تحتاج النموذج"
            }
        }
    }

    private fun updateFaceStatus() {
        lifecycleScope.launch {
            val faces = withContext(Dispatchers.IO) { faceIndexCoordinator.faceCount() }
            val embeddings = withContext(Dispatchers.IO) { faceIndexCoordinator.embeddingCount() }
            binding.faceStatusText.text = "FACE INDEX • ${faces} وجه • ${embeddings} بصمة"
        }
    }

    private fun buildVisualIndex() {
        lifecycleScope.launch {
            if (!semanticSearchService.isModelInstalled()) {
                showStatus("استورد نموذج MobileCLIP-S2 أولًا من Data Center أو من هذه الشاشة.")
                return@launch
            }
            val total = withContext(Dispatchers.IO) { database.imageDao().count() }
            if (total == 0) {
                showStatus("الفهرس فارغ. افهرس الصور أولًا.")
                return@launch
            }
            setBusy(true)
            binding.progressBar.max = total
            binding.progressBar.progress = 0
            binding.statusText.text = "تهيئة MobileCLIP-S2 وبناء الفهرس البصري..."
            try {
                semanticSearchService.indexAllImages { processed, count, embedded, skipped ->
                    runOnUiThread {
                        binding.progressBar.progress = processed
                        binding.counterText.text = "VISUAL INDEX • $processed/$count"
                        binding.statusText.text = "بناء الفهرس البصري: $processed / $count\nجديد: $embedded • موجود مسبقًا: $skipped"
                    }
                }
                val finalCount = semanticSearchService.imageEmbeddingCount()
                binding.modelStatusText.text = "● MobileCLIP-S2 جاهز • بصمات الصور: $finalCount"
                binding.statusText.text = "اكتمل الفهرس البصري. البحث بالصورة يعتمد الآن على embeddings محفوظة."
            } catch (e: Exception) {
                showStatus("فشل بناء الفهرس البصري: ${e.message}")
                Toast.makeText(this@MainActivity, "فشل بناء الفهرس البصري", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun buildFaceIndex() {
        lifecycleScope.launch {
            val total = withContext(Dispatchers.IO) { database.imageDao().count() }
            if (total == 0) {
                showStatus("الفهرس فارغ. افهرس الصور أولًا.")
                return@launch
            }
            setBusy(true)
            binding.progressBar.max = total.toInt().coerceAtLeast(1)
            binding.progressBar.progress = 0
            binding.statusText.text = "FACE INTELLIGENCE • MediaPipe + MobileFaceNet..."
            try {
                val result = withContext(Dispatchers.IO) {
                    faceIndexCoordinator.indexAllImages { progress ->
                        runOnUiThread {
                            binding.progressBar.progress = progress.processed
                            binding.counterText.text = "FACE INDEX • ${progress.processed}/${progress.total}"
                            binding.statusText.text = "تحليل الوجوه: ${progress.processed}/${progress.total}\nمكتشف: ${progress.detectedFaces} • بصمات: ${progress.indexedFaces} • فشل: ${progress.failedImages}"
                        }
                    }
                }
                val clusters = withContext(Dispatchers.IO) { faceIndexCoordinator.buildPersonClusters() }
                updateFaceStatus()
                binding.statusText.text = "اكتمل تحليل الوجوه.\nالوجوه: ${result.detectedFaces} • البصمات: ${result.indexedFaces}\nالمجموعات الجديدة: ${clusters.createdClusters} • الوجوه المرتبطة: ${clusters.assignedFaces}"
            } catch (e: Exception) {
                showStatus("فشل فهرسة الوجوه: ${e.message}")
                Toast.makeText(this@MainActivity, "فشل نظام الوجوه", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    /**
     * One user action now drives the complete local image pipeline:
     * image/OCR/object metadata -> faces -> person clustering -> optional MobileCLIP.
     * Each stage is independently guarded and recorded by FullIndexingCoordinator.
     */
    private fun indexImages(uris: List<Uri>) {
        lifecycleScope.launch {
            setBusy(true)
            binding.progressBar.max = uris.size.coerceAtLeast(1)
            binding.progressBar.progress = 0
            binding.statusText.text = "تهيئة خط أنابيب الفهرسة الكامل..."
            try {
                val result = withContext(Dispatchers.IO) {
                    fullIndexingCoordinator.indexUris(uris) { progress ->
                        runOnUiThread {
                            binding.progressBar.progress = progress.processed
                            binding.counterText.text = "FULL INDEX • ${progress.processed}/${progress.total}"
                            binding.statusText.text = "فهرسة كاملة: ${progress.processed}/${progress.total}\nصور: ${progress.imagesIndexed} • وجوه: ${progress.facesIndexed}/${progress.facesDetected} • بصري: ${progress.visualEmbedded} جديد / ${progress.visualSkipped} موجود • فشل: ${progress.imageFailures + progress.faceFailures + progress.visualFailures}"
                        }
                    }
                }
                loadAllImages()
                updateFaceStatus()
                updateModelStatus()
                binding.statusText.text = "اكتملت الفهرسة الكاملة.\nالصور: ${result.imagesIndexed}/${result.total} • الوجوه: ${result.facesIndexed}/${result.facesDetected} • بصمات MobileCLIP: ${result.visualEmbedded} جديدة / ${result.visualSkipped} موجودة • إخفاقات: ${result.imageFailures + result.faceFailures + result.visualFailures}"
            } catch (e: Exception) {
                showStatus("توقفت الفهرسة الكاملة بسبب خطأ: ${e.message}")
                Toast.makeText(this@MainActivity, "فشل خط أنابيب الفهرسة", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun performSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            showStatus("اكتب كلمات أو وصفًا للبحث.")
            return
        }
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val exact = database.imageDao().searchTextAndObjects(normalized)
                    val tokens = normalized.lowercase(Locale.getDefault())
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
                    scores.values.sortedWith(
                        compareByDescending<Pair<ImageEntity, Int>> { it.second }
                            .thenByDescending { it.first.dateTaken ?: 0L }
                    ).map { it.first }.ifEmpty { exact }
                }
                adapter.submitList(results)
                binding.counterText.text = "النتائج: ${results.size}"
                binding.statusText.text = if (results.isEmpty()) "لم توجد مطابقة للكلمات أو الكائنات: $normalized" else "${results.size} نتيجة • OCR + object labels.\nالبحث النصي الحالي يعتمد على OCR/object retrieval المحلي؛ Text Encoder غير متاح بعد."
            } catch (e: Exception) {
                showStatus("حدث خطأ أثناء البحث: ${e.message}")
            }
        }
    }

    private fun semanticSearch(queryUri: Uri) {
        lifecycleScope.launch {
            try {
                if (!semanticSearchService.isModelInstalled()) {
                    showStatus("استورد نموذج MobileCLIP-S2 أولًا من بطاقة النماذج.")
                    return@launch
                }
                val embeddings = withContext(Dispatchers.IO) { semanticSearchService.imageEmbeddingCount() }
                if (embeddings == 0L) {
                    showStatus("لا توجد بصمات بصرية. اضغط INDEX PHOTOS أو BUILD VISUAL INDEX أولًا.")
                    return@launch
                }
                setBusy(true)
                binding.statusText.text = "جاري تشغيل البحث البصري المحلي..."
                val results = withContext(Dispatchers.IO) { semanticSearchService.searchSimilarImages(queryUri, limit = 30) }
                adapter.submitList(results.map { it.image })
                binding.counterText.text = "النتائج الدلالية: ${results.size}"
                binding.statusText.text = "تم ترتيب ${results.size} صورة حسب التشابه البصري من embeddings المخزنة."
            } catch (e: Exception) {
                showStatus("تعذر البحث الدلالي: ${e.message}")
                Toast.makeText(this@MainActivity, "تعذر تشغيل MobileCLIP-S2", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
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

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.selectButton.isEnabled = !busy
        binding.importModelButton.isEnabled = !busy
        binding.dataCenterButton.isEnabled = !busy
        binding.buildVisualIndexButton.isEnabled = !busy
        binding.buildFaceIndexButton.isEnabled = !busy
        binding.searchButton.isEnabled = !busy
        binding.semanticSearchButton.isEnabled = !busy
    }

    private fun showStatus(text: String) { binding.statusText.text = text }

    override fun onDestroy() {
        fullIndexingCoordinator.close()
        semanticSearchService.close()
        faceIndexCoordinator.close()
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
