package com.example.personalmemoryai.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.personalmemoryai.databinding.ActivityReverseImageSearchBinding
import com.example.personalmemoryai.indexing.UnifiedVisualIndexWorker
import com.example.personalmemoryai.indexing.VisualIndexWorkScheduler
import com.example.personalmemoryai.reverseimage.ReverseImageSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/** Existing reverse-image screen. Advanced Visual Intelligence is a separate screen. */
class ReverseImageSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReverseImageSearchBinding
    private lateinit var service: ReverseImageSearchService
    private lateinit var adapter: ReverseImageResultAdapter
    private var searchJob: Job? = null
    private var indexObservationJob: Job? = null
    private var observedWorkId: UUID? = null

    private val queryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runSearch(uri)
    }

    private val corpusPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uris = result.data?.getParcelableArrayListExtra<Parcelable>(BulkImagePickerActivity.RESULT_URIS)
            ?.mapNotNull { it as? Uri }
            .orEmpty()
        if (uris.isEmpty()) showStatus("لم يتم اختيار أي صورة لإضافتها إلى Corpus البحث العكسي.") else addImages(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReverseImageSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        service = ReverseImageSearchService(applicationContext)
        adapter = ReverseImageResultAdapter { result -> ImageViewerActivity.start(this, result.displayUri()) }
        binding.resultsRecyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.resultsRecyclerView.adapter = adapter

        binding.queryImageButton.setOnClickListener {
            adapter.clear()
            queryPicker.launch("image/*")
        }
        binding.addImagesButton.setOnClickListener {
            adapter.clear()
            binding.resultsRecyclerView.scrollToPosition(0)
            corpusPicker.launch(BulkImagePickerActivity.launchIntent("REVERSE IMAGE / LOCAL CORPUS"))
        }
        binding.buildIndexButton.setOnClickListener { startSharedIndex(false) }
        binding.rebuildIndexButton.setOnClickListener { startSharedIndex(true) }
        refreshCount()
        observeExistingIndexWork()
    }

    private fun addImages(uris: List<Uri>) {
        lifecycleScope.launch {
            setBusy(true)
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.isIndeterminate = true
            binding.progressPercentText.text = "جارٍ تجهيز الصور  •  0/${uris.size}"
            try {
                val added = withContext(Dispatchers.IO) { service.addImages(uris) }
                binding.statusText.text = "تمت إضافة $added صورة إلى Corpus المشترك. لم تُفهرس مرتين."
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

    private fun startSharedIndex(rebuild: Boolean) {
        observedWorkId = VisualIndexWorkScheduler.enqueue(applicationContext, rebuild)
        observeExistingIndexWork(observedWorkId)
    }

    private fun observeExistingIndexWork(workId: UUID? = null) {
        indexObservationJob?.cancel()
        indexObservationJob = lifecycleScope.launch {
            while (!isFinishing && !isDestroyed) {
                val info = withContext(Dispatchers.IO) {
                    val wm = WorkManager.getInstance(applicationContext)
                    if (workId != null) wm.getWorkInfoById(workId).get()
                    else wm.getWorkInfosForUniqueWork(UnifiedVisualIndexWorker.WORK_NAME).get().firstOrNull { !it.state.isFinished }
                }
                if (info == null) break
                observedWorkId = info.id
                renderIndexWork(info)
                if (info.state.isFinished) break
                delay(700)
            }
            setBusy(false)
            refreshCount()
        }
    }

    private fun renderIndexWork(info: WorkInfo) {
        val p = info.progress
        val processed = p.getInt(UnifiedVisualIndexWorker.KEY_PROCESSED, 0)
        val total = p.getInt(UnifiedVisualIndexWorker.KEY_TOTAL, 0)
        val percent = p.getInt("percent", 0)
        binding.progressBar.visibility = if (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED) View.VISIBLE else View.GONE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = total.coerceAtLeast(1)
        binding.progressBar.progress = processed.coerceIn(0, binding.progressBar.max)
        binding.progressPercentText.text = String.format(Locale.US, "%d%%  •  %d/%d", percent, processed, total)
        binding.counterText.text = "نجح ${p.getInt(UnifiedVisualIndexWorker.KEY_INDEXED, 0)}  •  تخطي ${p.getInt(UnifiedVisualIndexWorker.KEY_SKIPPED, 0)}  •  Local features ${p.getInt(UnifiedVisualIndexWorker.KEY_LOCAL_FEATURES, 0)}  •  فشل ${p.getInt(UnifiedVisualIndexWorker.KEY_FAILED, 0)}"
        binding.statusText.text = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> "الفهرسة المشتركة في الخلفية • Haar + Classical V4 + Advanced Visual"
            WorkInfo.State.SUCCEEDED -> "اكتمل الفهرس المشترك لجميع المحركات."
            WorkInfo.State.FAILED -> "فشل الفهرس: ${info.outputData.getString("error") ?: "خطأ غير محدد"}"
            WorkInfo.State.CANCELLED -> "تم إلغاء الفهرسة."
            else -> info.state.name
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
                val results = service.search(uri, limit = 50, minimumSimilarity = threshold) { progress ->
                    runOnUiThread {
                        val total = progress.total.coerceAtLeast(1)
                        val processed = progress.processed.coerceIn(0, total)
                        binding.progressBar.max = total
                        binding.progressBar.progress = processed
                        val percent = ((processed * 100L) / total).toInt().coerceIn(0, 100)
                        binding.progressPercentText.text = String.format(Locale.US, "%d%%  •  %d/%d", percent, processed, total)
                        binding.counterText.text = "${progress.stage} • ${progress.processed}/${progress.total} • Local ${progress.localVerified} • SIFT ${progress.siftVerified}"
                        binding.statusText.text = progress.stage
                    }
                }
                adapter.submitList(results)
                binding.progressBar.progress = binding.progressBar.max
                binding.progressPercentText.text = "100%  •  اكتمل البحث"
                binding.counterText.text = "${results.size} نتيجة • الحد الأدنى ${String.format(Locale.US, "%.0f", threshold * 100)}%"
                binding.statusText.text = if (results.isEmpty()) "اكتمل البحث • لا توجد صور ضمن العتبة الحالية." else "اكتمل البحث • ${results.size} نتيجة مرتبة بالأدلة البصرية."
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) showError("تعذر تنفيذ البحث العكسي: ${t.message}")
            } finally {
                if (!isFinishing && !isDestroyed) setBusy(false)
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
        binding.queryImageButton.isEnabled = !value
        binding.addImagesButton.isEnabled = !value
        binding.buildIndexButton.isEnabled = !value
        binding.rebuildIndexButton.isEnabled = !value
        binding.thresholdSeek.isEnabled = !value
    }

    private fun showStatus(text: String) { binding.statusText.text = text }

    private fun showError(message: String) {
        binding.statusText.text = message
        binding.progressPercentText.text = "FAILED"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        indexObservationJob?.cancel()
        service.close()
        super.onDestroy()
    }

    private fun ReverseImageSearchService.Result.displayUri(): String =
        item.filePath?.takeIf { it.isNotBlank() }?.let { "file://$it" } ?: item.uri
}
