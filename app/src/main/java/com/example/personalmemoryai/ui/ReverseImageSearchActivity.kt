package com.example.personalmemoryai.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.personalmemoryai.databinding.ActivityReverseImageSearchBinding
import com.example.personalmemoryai.reverseimage.ReverseImageSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Standalone digiKam-style local reverse-image search screen. */
class ReverseImageSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReverseImageSearchBinding
    private lateinit var service: ReverseImageSearchService
    private lateinit var adapter: ReverseImageResultAdapter

    private val queryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) runSearch(uri) }
    private val corpusPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (!uris.isNullOrEmpty()) addImages(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReverseImageSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        service = ReverseImageSearchService(applicationContext)
        adapter = ReverseImageResultAdapter { result -> openUri(result.item.uri) }
        binding.resultsRecyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.resultsRecyclerView.adapter = adapter
        binding.queryImageButton.setOnClickListener { queryPicker.launch("image/*") }
        binding.addImagesButton.setOnClickListener { corpusPicker.launch(arrayOf("image/*")) }
        binding.buildIndexButton.setOnClickListener { buildIndex(false) }
        binding.rebuildIndexButton.setOnClickListener { buildIndex(true) }
        refreshCount()
    }

    private fun addImages(uris: List<Uri>) {
        lifecycleScope.launch {
            setBusy(true)
            try {
                for (uri in uris) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                val added = withContext(Dispatchers.IO) { service.addImages(uris) }
                binding.statusText.text = "تمت إضافة $added صورة إلى Corpus البحث العكسي المستقل. ابنِ الفهرس الآن."
                refreshCount()
            } catch (t: Throwable) { showError("فشل إضافة الصور: ${t.message}") }
            finally { setBusy(false) }
        }
    }

    private fun buildIndex(rebuild: Boolean) {
        lifecycleScope.launch {
            setBusy(true)
            binding.progressBar.progress = 0
            binding.statusText.text = if (rebuild) "إعادة بناء فهرس Haar..." else "بناء فهرس Haar..."
            try {
                val result = withContext(Dispatchers.Default) {
                    service.buildIndex(rebuild) { progress ->
                        runOnUiThread {
                            binding.progressBar.max = progress.total.coerceAtLeast(1)
                            binding.progressBar.progress = progress.processed
                            binding.counterText.text = "${progress.processed}/${progress.total} • جديد ${progress.indexed} • متخطى ${progress.skipped} • فشل ${progress.failed}"
                        }
                    }
                }
                binding.statusText.text = "اكتمل الفهرس • جديد ${result.indexed} • موجود ${result.skipped} • فشل ${result.failed}"
                refreshCount()
            } catch (t: Throwable) { showError("فشل بناء الفهرس: ${t.message}") }
            finally { setBusy(false) }
        }
    }

    private fun runSearch(uri: Uri) {
        lifecycleScope.launch {
            setBusy(true)
            binding.statusText.text = "حساب بصمة صورة البحث ثم المقارنة محليًا..."
            try {
                val threshold = binding.thresholdSeek.progress / 100f
                val results = withContext(Dispatchers.Default) { service.search(uri, limit = 50, minimumSimilarity = threshold) }
                adapter.submitList(results)
                binding.counterText.text = "${results.size} نتيجة • الحد الأدنى ${String.format(Locale.US, "%.0f", threshold * 100)}%"
                binding.statusText.text = if (results.isEmpty()) "لا توجد صور ضمن العتبة الحالية." else "تم العثور على ${results.size} صورة مرتبة حسب التشابه البصري."
            } catch (t: Throwable) { showError("تعذر تنفيذ البحث العكسي: ${t.message}") }
            finally { setBusy(false) }
        }
    }

    private fun refreshCount() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { service.itemCount() }
            val fingerprints = withContext(Dispatchers.IO) { service.fingerprintCount() }
            binding.indexCountText.text = "Corpus: $items صورة • بصمات Haar: $fingerprints"
        }
    }

    private fun openUri(uri: String) { ImageViewerActivity.start(this, uri) }

    private fun setBusy(value: Boolean) {
        binding.progressBar.visibility = if (value) View.VISIBLE else View.GONE
        binding.queryImageButton.isEnabled = !value
        binding.addImagesButton.isEnabled = !value
        binding.buildIndexButton.isEnabled = !value
        binding.rebuildIndexButton.isEnabled = !value
        binding.thresholdSeek.isEnabled = !value
    }

    private fun showError(message: String) {
        binding.statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { service.close(); super.onDestroy() }
}
