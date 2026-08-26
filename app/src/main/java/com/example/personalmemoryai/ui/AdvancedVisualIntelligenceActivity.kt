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
import com.example.personalmemoryai.advancedvisual.AdvancedVisualIntelligenceService
import com.example.personalmemoryai.databinding.ActivityAdvancedVisualIntelligenceBinding
import com.example.personalmemoryai.indexing.UnifiedVisualIndexWorker
import com.example.personalmemoryai.indexing.VisualIndexWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class AdvancedVisualIntelligenceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdvancedVisualIntelligenceBinding
    private lateinit var service: AdvancedVisualIntelligenceService
    private lateinit var adapter: AdvancedVisualResultAdapter
    private var searchJob: Job? = null
    private var observedWorkId: UUID? = null

    private val corpusPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uris = result.data?.getParcelableArrayListExtra<Parcelable>(BulkImagePickerActivity.RESULT_URIS)
            ?.mapNotNull { it as? Uri }
            .orEmpty()
        if (uris.isEmpty()) {
            binding.statusText.text = "لم يتم اختيار صور لإضافتها إلى Corpus المشترك."
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            setBusy(true)
            try {
                val added = withContext(Dispatchers.IO) {
                    com.example.personalmemoryai.reverseimage.ReverseImageSearchService(applicationContext).use { it.addImages(uris) }
                }
                binding.statusText.text = "تمت إضافة $added صورة إلى Corpus المشترك. لا توجد فهرسة ثانية للصورة عند تشغيل المحركات."
                refreshCounts()
            } catch (t: Throwable) {
                showError("تعذر إضافة الصور: ${t.message}")
            } finally { setBusy(false) }
        }
    }

    private val queryPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runSearch(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedVisualIntelligenceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        service = AdvancedVisualIntelligenceService(applicationContext)
        adapter = AdvancedVisualResultAdapter { path ->
            if (path.isNullOrBlank()) return@AdvancedVisualResultAdapter
            ImageViewerActivity.start(this, "file://$path")
        }
        binding.resultsRecyclerView.layoutManager = GridLayoutManager(this, 1)
        binding.resultsRecyclerView.adapter = adapter

        binding.addImagesButton.setOnClickListener {
            corpusPicker.launch(BulkImagePickerActivity.launchIntent("ADVANCED VISUAL / SHARED CORPUS"))
        }
        binding.buildIndexButton.setOnClickListener { startSharedIndex(false) }
        binding.rebuildIndexButton.setOnClickListener { startSharedIndex(true) }
        binding.queryImageButton.setOnClickListener {
            adapter.submitList(emptyList())
            queryPicker.launch("image/*")
        }
        binding.cancelSearchButton.setOnClickListener { searchJob?.cancel(); binding.statusText.text = "تم إلغاء البحث الحالي."; setBusy(false) }
        refreshCounts()
        pollSharedIndex()
    }

    private fun startSharedIndex(rebuild: Boolean) {
        val id = VisualIndexWorkScheduler.enqueue(applicationContext, rebuild)
        observedWorkId = id
        binding.statusText.text = "تم تشغيل الفهرسة المشتركة: Reverse Image + Advanced Visual Intelligence."
        observeWork(id)
    }

    private fun observeWork(id: UUID) {
        lifecycleScope.launch {
            setBusy(true)
            while (true) {
                val info = withContext(Dispatchers.IO) {
                    WorkManager.getInstance(applicationContext).getWorkInfoById(id).get()
                } ?: break
                val p = info.progress
                val processed = p.getInt(UnifiedVisualIndexWorker.KEY_PROCESSED, 0)
                val total = p.getInt(UnifiedVisualIndexWorker.KEY_TOTAL, 0)
                val percent = p.getInt("percent", 0)
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.max = total.coerceAtLeast(1)
                binding.progressBar.progress = processed.coerceIn(0, binding.progressBar.max)
                binding.progressPercentText.text = String.format(Locale.US, "%d%%  •  %d/%d", percent, processed, total)
                binding.counterText.text = "Indexed ${p.getInt(UnifiedVisualIndexWorker.KEY_INDEXED, 0)} • Skipped ${p.getInt(UnifiedVisualIndexWorker.KEY_SKIPPED, 0)} • Failed ${p.getInt(UnifiedVisualIndexWorker.KEY_FAILED, 0)}"
                binding.statusText.text = when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> "Shared visual indexing in progress…"
                    WorkInfo.State.SUCCEEDED -> "اكتملت الفهرسة المشتركة لجميع المحركات."
                    WorkInfo.State.FAILED -> "فشلت الفهرسة: ${info.outputData.getString("error") ?: "خطأ غير محدد"}"
                    WorkInfo.State.CANCELLED -> "تم إلغاء الفهرسة."
                    else -> info.state.name
                }
                if (info.state.isFinished) break
                delay(700)
            }
            setBusy(false)
            refreshCounts()
        }
    }

    private fun pollSharedIndex() {
        lifecycleScope.launch {
            val infos = withContext(Dispatchers.IO) {
                WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(UnifiedVisualIndexWorker.WORK_NAME).get()
            }
            val running = infos.firstOrNull { !it.state.isFinished }
            if (running != null) observeWork(running.id)
        }
    }

    private fun runSearch(uri: Uri) {
        searchJob?.cancel()
        adapter.submitList(emptyList())
        val threshold = (binding.thresholdSeek.progress / 100f).coerceIn(0f, 1f)
        searchJob = lifecycleScope.launch {
            setBusy(true)
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.isIndeterminate = false
            binding.progressBar.max = 100
            binding.progressBar.progress = 0
            try {
                val results = service.search(uri, 50, threshold) { processed, total, stage ->
                    runOnUiThread {
                        val percent = if (total > 0) ((processed * 100L) / total).toInt().coerceIn(0, 100) else 0
                        binding.progressBar.progress = percent
                        binding.progressPercentText.text = String.format(Locale.US, "%d%%  •  %d/%d", percent, processed, total)
                        binding.statusText.text = stage
                        binding.counterText.text = "Advanced evidence fusion • threshold ${String.format(Locale.US, "%.0f", threshold * 100)}%"
                    }
                }
                adapter.submitList(results)
                binding.progressBar.progress = 100
                binding.progressPercentText.text = "100%  •  اكتمل"
                binding.statusText.text = if (results.isEmpty()) "لا توجد نتائج فوق العتبة الحالية." else "اكتمل البحث • كل نتيجة تحمل تفسيرًا لمصدر التشابه."
                binding.counterText.text = "${results.size} نتيجة قابلة للتفسير"
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) showError("تعذر تنفيذ Advanced Visual Intelligence: ${t.message}")
            } finally { setBusy(false) }
        }
    }

    private fun refreshCounts() {
        lifecycleScope.launch {
            val corpus = withContext(Dispatchers.IO) { com.example.personalmemoryai.reverseimage.ReverseImageSearchService(applicationContext).use { it.itemCount() } }
            val advanced = service.fingerprintCount()
            binding.indexCountText.text = "SHARED CORPUS: $corpus • ADVANCED INDEX: $advanced"
        }
    }

    private fun setBusy(value: Boolean) {
        binding.progressBar.visibility = if (value) View.VISIBLE else View.GONE
        binding.addImagesButton.isEnabled = !value
        binding.buildIndexButton.isEnabled = !value
        binding.rebuildIndexButton.isEnabled = !value
        binding.queryImageButton.isEnabled = !value
        binding.cancelSearchButton.isEnabled = value
        binding.thresholdSeek.isEnabled = !value
    }

    private fun showError(text: String) {
        binding.statusText.text = text
        binding.progressPercentText.text = "FAILED"
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        observedWorkId = null
        service.close()
        super.onDestroy()
    }
}
