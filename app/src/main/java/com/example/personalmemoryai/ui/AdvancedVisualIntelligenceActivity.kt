package com.example.personalmemoryai.ui

import android.net.Uri
import android.os.Bundle
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
import com.example.personalmemoryai.indexing.ImageCorpusImportScheduler
import com.example.personalmemoryai.indexing.ImageCorpusImportWorker
import com.example.personalmemoryai.indexing.UnifiedVisualIndexWorker
import com.example.personalmemoryai.indexing.VisualIndexWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
        val queuePath = result.data?.getStringExtra(BulkImagePickerActivity.RESULT_QUEUE_FILE)
        if (queuePath.isNullOrBlank()) {
            binding.statusText.text = "لم يتم إنشاء قائمة الصور المختارة."
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            setBusy(true)
            try {
                val id = withContext(Dispatchers.IO) {
                    ImageCorpusImportScheduler.enqueue(applicationContext, queuePath)
                }
                binding.statusText.text = "بدأ استيراد الصور في الخلفية • العملية: ${id.toString().take(8)}"
                observeImport(id)
            } catch (t: Throwable) {
                showError("تعذر بدء استيراد الصور: ${t.message}")
                setBusy(false)
            }
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
        pollImport()
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
                val info = withContext(Dispatchers.IO) { WorkManager.getInstance(applicationContext).getWorkInfoById(id).get() } ?: break
                val p = info.progress
                val processed = p.getInt(UnifiedVisualIndexWorker.KEY_PROCESSED, 0)
                val total = p.getInt(UnifiedVisualIndexWorker.KEY_TOTAL, 0)
                val percent = p.getInt("percent", 0)
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.max = total.coerceAtLeast(1)
                binding.progressBar.progress = processed.coerceIn(0, binding.progressBar.max)
                binding.progressPercentText.text = String.format(Locale.US, "%d%%  •  %d/%d", percent, processed, total)
                binding.counterText.text = "نجح ${p.getInt(UnifiedVisualIndexWorker.KEY_INDEXED, 0)} • تخطي ${p.getInt(UnifiedVisualIndexWorker.KEY_SKIPPED, 0)} • فشل ${p.getInt(UnifiedVisualIndexWorker.KEY_FAILED, 0)}"
                binding.statusText.text = when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> "الفهرسة المشتركة في الخلفية • Haar + Classical V4 + Advanced Visual"
                    WorkInfo.State.SUCCEEDED -> "اكتمل الفهرس المشترك لجميع المحركات."
                    WorkInfo.State.FAILED -> "فشل الفهرس: ${info.outputData.getString("error") ?: "خطأ غير محدد"}"
                    WorkInfo.State.CANCELLED -> "تم إلغاء الفهرسة."
                    else -> info.state.name
                }
                if (info.state.isFinished) break
                delay(600)
            }
            setBusy(false)
            refreshCounts()
        }
    }

    private fun pollSharedIndex() {
        lifecycleScope.launch {
            val infos = withContext(Dispatchers.IO) { WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(UnifiedVisualIndexWorker.WORK_NAME).get() }
            infos.firstOrNull { !it.state.isFinished }?.let { observeWork(it.id) }
        }
    }

    private fun observeImport(id: UUID) {
        lifecycleScope.launch {
            setBusy(true)
            while (true) {
                val info = withContext(Dispatchers.IO) { WorkManager.getInstance(applicationContext).getWorkInfoById(id).get() } ?: break
                val p = info.progress
                val processed = p.getInt(ImageCorpusImportWorker.KEY_PROCESSED, 0)
                val total = p.getInt(ImageCorpusImportWorker.KEY_TOTAL, 0)
                val percent = p.getInt(ImageCorpusImportWorker.KEY_PERCENT, 0)
                binding.progressBar.visibility = if (info.state.isFinished) View.GONE else View.VISIBLE
                binding.progressBar.max = total.coerceAtLeast(1)
                binding.progressBar.progress = processed.coerceIn(0, binding.progressBar.max)
                binding.progressPercentText.text = String.format(Locale.US, "%d%%  •  %d/%d", percent, processed, total)
                binding.counterText.text = "مضاف ${p.getInt(ImageCorpusImportWorker.KEY_ADDED, 0)} • تخطي ${p.getInt(ImageCorpusImportWorker.KEY_SKIPPED, 0)} • فشل ${p.getInt(ImageCorpusImportWorker.KEY_FAILED, 0)}"
                binding.statusText.text = when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> "استيراد الصور في الخلفية • لا حاجة لإبقاء الشاشة مفتوحة"
                    WorkInfo.State.SUCCEEDED -> "اكتمل استيراد الصور. يمكنك الآن بناء الفهرس المشترك."
                    WorkInfo.State.FAILED -> "فشل استيراد الصور: ${info.outputData.getString("error") ?: "خطأ غير محدد"}"
                    WorkInfo.State.CANCELLED -> "تم إلغاء استيراد الصور."
                    else -> info.state.name
                }
                if (info.state.isFinished) break
                delay(600)
            }
            setBusy(false)
            refreshCounts()
        }
    }

    private fun pollImport() {
        lifecycleScope.launch {
            val infos = withContext(Dispatchers.IO) { WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(ImageCorpusImportWorker.WORK_NAME).get() }
            infos.firstOrNull { !it.state.isFinished }?.let { observeImport(it.id) }
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
