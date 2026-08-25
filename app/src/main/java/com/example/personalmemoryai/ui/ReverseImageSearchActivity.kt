package com.example.personalmemoryai.ui

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** First-class local reverse-image search screen inside the main PMAI shell. */
class ReverseImageSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReverseImageSearchBinding
    private lateinit var service: ReverseImageSearchService
    private lateinit var adapter: ReverseImageResultAdapter
    private var searchJob: Job? = null

    private val queryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runSearch(uri)
    }

    private val corpusPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) {
            showStatus("لم يتم اختيار أي صورة لإضافتها إلى Corpus البحث العكسي.")
        } else {
            addImages(uris.take(1000))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReverseImageSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        service = ReverseImageSearchService(applicationContext)
        adapter = ReverseImageResultAdapter { result ->
            ImageViewerActivity.start(this, result.displayUri())
        }
        binding.resultsRecyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.resultsRecyclerView.adapter = adapter

        binding.queryImageButton.setOnClickListener {
            adapter.clear()
            queryPicker.launch("image/*")
        }
        binding.addImagesButton.setOnClickListener {
            adapter.clear()
            binding.resultsRecyclerView.scrollToPosition(0)
            corpusPicker.launch("image/*")
        }
        binding.buildIndexButton.setOnClickListener { buildIndex(false) }
        binding.rebuildIndexButton.setOnClickListener { buildIndex(true) }
        refreshCount()
    }

    private fun addImages(uris: List<Uri>) {
        lifecycleScope.launch {
            setBusy(true)
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.isIndeterminate = true
            binding.progressPercentText.text = "جارٍ تجهيز الصور  •  0/${uris.size}"
            try {
                val added = withContext(Dispatchers.IO) { service.addImages(uris) }
                binding.statusText.text = "تمت إضافة $added صورة إلى Corpus المحلي. أعد بناء الفهرس عند الحاجة."
                binding.progressPercentText.text = "اكتملت الإضافة  •  $added/${uris.size}"
                refreshCount()
            } catch (t: Throwable) {
                showError("فشل إضافة الصور: ${t.message}")
            } finally {
                binding.progressBar.isIndeterminate = false
                setBusy(false)
            }
        }
    }

    private fun buildIndex(rebuild: Boolean) {
        lifecycleScope.launch {
            setBusy(true)
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.isIndeterminate = false
            binding.progressBar.progress = 0
            binding.progressPercentText.text = "0%  •  0/0"
            binding.counterText.text = "تهيئة الفهرس…"
            binding.statusText.text = if (rebuild) {
                "إعادة بناء Haar + Classical Visual Index…"
            } else {
                "بناء Haar + Classical Visual Index…"
            }
            val startedAt = System.currentTimeMillis()
            try {
                service.buildIndex(rebuild) { progress ->
                    runOnUiThread {
                        val total = progress.total.coerceAtLeast(1)
                        val processed = progress.processed.coerceIn(0, total)
                        val percent = ((processed * 100L) / total).toInt().coerceIn(0, 100)
                        val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
                        val rate = processed.toDouble() / elapsedSec.toDouble()
                        val remaining = (total - processed).coerceAtLeast(0)
                        val etaSec = if (rate > 0.0) (remaining / rate).toLong() else 0L
                        binding.progressBar.max = total
                        binding.progressBar.progress = processed
                        binding.progressPercentText.text = String.format(
                            Locale.US, "%d%%  •  %d/%d  •  %.1f صورة/ث  •  ETA %s",
                            percent, processed, total, rate, formatDuration(etaSec)
                        )
                        binding.counterText.text = "نجح ${progress.indexed}  •  تخطي ${progress.skipped}  •  Local features ${progress.localFeatureIndexed}  •  فشل ${progress.failed}"
                        binding.statusText.text = "فهرسة الصورة $processed من $total"
                    }
                }
                binding.progressBar.progress = binding.progressBar.max
                binding.progressPercentText.text = "100%  •  ${binding.progressBar.max}/${binding.progressBar.max}  •  اكتمل"
                binding.statusText.text = "اكتمل الفهرس • Haar + pHash + dHash + HSV256 + Shape + AKAZE/RANSAC + SIFT."
                refreshCount()
            } catch (t: Throwable) {
                showError("فشل بناء الفهرس: ${t.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runSearch(uri: Uri) {
        searchJob?.cancel()
        adapter.clear()
        binding.resultsRecyclerView.scrollToPosition(0)
        val threshold = (binding.thresholdSeek.progress / 100f).coerceIn(0f, 1f)

        searchJob = lifecycleScope.launch {
            setBusy(true)
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.isIndeterminate = false
            binding.progressBar.progress = 0
            binding.progressPercentText.text = "تحضير صورة البحث…"
            binding.counterText.text = "البحث السابق تم مسحه • بدء بحث جديد"
            binding.statusText.text = "بدء البحث المحلي…"
            try {
                val results = service.search(
                    uri,
                    limit = 50,
                    minimumSimilarity = threshold
                ) { progress ->
                    runOnUiThread {
                        val total = progress.total.coerceAtLeast(1)
                        val processed = progress.processed.coerceIn(0, total)
                        binding.progressBar.max = total
                        binding.progressBar.progress = processed
                        val percent = ((processed * 100L) / total).toInt().coerceIn(0, 100)
                        binding.progressPercentText.text = String.format(
                            Locale.US,
                            "%d%%  •  %d/%d",
                            percent, processed, total
                        )
                        binding.counterText.text = when (progress.stage) {
                            "البحث العالمي" -> "فحص Corpus • ${progress.processed}/${progress.total}"
                            "التحقق الهندسي AKAZE/RANSAC" -> "التحقق المحلي • ${progress.processed}/${progress.total} • نتائج مؤكدة ${progress.localVerified}"
                            "التحقق الإضافي SIFT/RANSAC" -> "SIFT • ${progress.processed}/${progress.total} • اكتمل ${progress.siftVerified}"
                            else -> progress.stage
                        }
                        binding.statusText.text = progress.stage
                    }
                }
                adapter.submitList(results)
                binding.progressBar.progress = binding.progressBar.max
                binding.progressPercentText.text = "100%  •  اكتمل البحث"
                binding.counterText.text = "${results.size} نتيجة • الحد الأدنى ${String.format(Locale.US, "%.0f", threshold * 100)}%"
                binding.statusText.text = if (results.isEmpty()) {
                    "اكتمل البحث • لا توجد صور ضمن العتبة الحالية."
                } else {
                    "اكتمل البحث • ${results.size} نتيجة مرتبة بالأدلة البصرية."
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    showError("تعذر تنفيذ البحث العكسي: ${t.message}")
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setBusy(false)
                }
            }
        }
    }

    private fun refreshCount() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { service.itemCount() }
            val haar = withContext(Dispatchers.IO) { service.fingerprintCount() }
            val classical = withContext(Dispatchers.IO) { service.classicalFingerprintCount() }
            binding.indexCountText.text = "Corpus: $items صورة • Haar: $haar • Classical: $classical"
        }
    }

    private fun setBusy(value: Boolean) {
        binding.progressBar.visibility = if (value) View.VISIBLE else View.GONE
        binding.queryImageButton.isEnabled = !value
        binding.addImagesButton.isEnabled = !value
        binding.buildIndexButton.isEnabled = !value
        binding.rebuildIndexButton.isEnabled = !value
        binding.thresholdSeek.isEnabled = !value
    }

    private fun showStatus(text: String) {
        binding.statusText.text = text
    }

    private fun showError(message: String) {
        binding.statusText.text = message
        binding.progressPercentText.text = "FAILED"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) return "—"
        val minutes = seconds / 60L
        val remaining = seconds % 60L
        return if (minutes > 0L) "${minutes}د ${remaining}ث" else "${remaining}ث"
    }

    override fun onDestroy() {
        searchJob?.cancel()
        service.close()
        super.onDestroy()
    }

    private fun ReverseImageSearchService.Result.displayUri(): String =
        item.filePath?.takeIf { it.isNotBlank() }?.let { "file://$it" } ?: item.uri
}
