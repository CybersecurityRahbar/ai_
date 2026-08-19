package com.example.personalmemoryai.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.data.DataBackupManager
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.databinding.ActivityDataCenterBinding
import com.example.personalmemoryai.semantic.MobileClipModelManager
import com.example.personalmemoryai.vision.FaceNet512ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class DataCenterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDataCenterBinding
    private lateinit var backupManager: DataBackupManager
    private lateinit var modelManager: MobileClipModelManager
    private lateinit var faceNetManager: FaceNet512ModelManager

    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) exportBackup(uri) }

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importBackup(uri) }

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importModel(uri) }

    private val faceModelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFaceNetModel(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        backupManager = DataBackupManager(applicationContext)
        modelManager = MobileClipModelManager(applicationContext)
        faceNetManager = FaceNet512ModelManager(applicationContext)

        binding.backButton.setOnClickListener { finish() }
        binding.exportBackupButton.setOnClickListener {
            exportPicker.launch("PersonalMemory_Backup_${System.currentTimeMillis()}.pmai")
        }
        binding.importBackupButton.setOnClickListener {
            importPicker.launch(arrayOf("application/octet-stream", "*/*"))
        }
        binding.importModelButton.setOnClickListener {
            modelPicker.launch(arrayOf("application/octet-stream", "application/tflite", "*/*"))
        }
        binding.removeModelButton.setOnClickListener {
            modelManager.deleteModel()
            updateStats()
            toast("تم حذف نسخة MobileCLIP المحلية")
        }
        binding.importFaceNetModelButton.setOnClickListener {
            faceModelPicker.launch(arrayOf("application/octet-stream", "application/tflite", "*/*"))
        }
        binding.removeFaceNetModelButton.setOnClickListener {
            faceNetManager.deleteModel()
            updateStats()
            toast("تم حذف نسخة FaceNet-512 المحلية")
        }

        updateStats()
    }

    private fun updateStats() {
        lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(applicationContext)
                Stats(db.imageDao().count(), db.faceDao().count(), db.personDao().count(), db.embeddingDao().count())
            }
            binding.imagesValue.text = stats.images.toString()
            binding.facesValue.text = stats.faces.toString()
            binding.personsValue.text = stats.persons.toString()
            binding.embeddingsValue.text = stats.embeddings.toString()
            binding.databaseValue.text = formatBytes(getDatabasePath("personal_memory.db").length())
            binding.modelValue.text = if (modelManager.isInstalled()) "READY • ${formatBytes(modelManager.installedSizeBytes())}" else "NOT INSTALLED"
            binding.faceNetModelValue.text = if (faceNetManager.isInstalled()) {
                "READY • ${formatBytes(faceNetManager.installedSizeBytes())} • 160×160 • 512-D"
            } else {
                "NOT INSTALLED • IMPORT facenet_512.tflite"
            }
        }
    }

    private fun exportBackup(uri: android.net.Uri) {
        lifecycleScope.launch {
            setBusy(true, "إنشاء النسخة الاحتياطية...")
            try {
                val result = backupManager.exportBackup(uri) { percent ->
                    runOnUiThread { binding.progressBar.progress = percent }
                }
                updateStats()
                val message = "تم إنشاء النسخة • ${result.copiedImages}/${result.imageCount} صورة"
                binding.statusText.text = if (result.missingImageIds.isEmpty()) message else "$message • تعذر نسخ ${result.missingImageIds.size} صورة"
                toast(message)
            } catch (e: Exception) {
                binding.statusText.text = "فشل التصدير: ${e.message}"
                toast("فشل إنشاء النسخة الاحتياطية")
            } finally { setBusy(false, "SYSTEM READY") }
        }
    }

    private fun importBackup(uri: android.net.Uri) {
        lifecycleScope.launch {
            setBusy(true, "استعادة قاعدة المعرفة...")
            try {
                val result = backupManager.importBackup(uri) { percent ->
                    runOnUiThread { binding.progressBar.progress = percent }
                }
                updateStats()
                val message = "تمت الاستعادة • ${result.restoredImages}/${result.imageCount} صورة"
                binding.statusText.text = message
                toast(message)
            } catch (e: Exception) {
                binding.statusText.text = "فشل الاستعادة: ${e.message}"
                toast("فشل استيراد النسخة الاحتياطية")
            } finally { setBusy(false, "SYSTEM READY") }
        }
    }

    private fun importModel(uri: android.net.Uri) {
        lifecycleScope.launch {
            setBusy(true, "استيراد MobileCLIP-S2 FP16...")
            try {
                withContext(Dispatchers.IO) {
                    modelManager.importModel(uri) { copied, total ->
                        val percent = if (total > 0) ((copied * 100L) / total).toInt().coerceIn(0, 100) else 0
                        runOnUiThread { binding.progressBar.progress = percent }
                    }
                }
                updateStats()
                binding.statusText.text = "MobileCLIP-S2 محفوظ محليًا بشكل دائم داخل مساحة التطبيق."
                toast("تم تثبيت MobileCLIP-S2")
            } catch (e: Exception) {
                binding.statusText.text = "فشل النموذج: ${e.message}"
                toast("فشل استيراد النموذج")
            } finally { setBusy(false, "SYSTEM READY") }
        }
    }

    private fun importFaceNetModel(uri: android.net.Uri) {
        lifecycleScope.launch {
            setBusy(true, "استيراد FaceNet-512 والتحقق من البنية...")
            try {
                withContext(Dispatchers.IO) {
                    faceNetManager.importModel(uri) { copied, total ->
                        val percent = if (total > 0) ((copied * 100L) / total).toInt().coerceIn(0, 100) else 0
                        runOnUiThread { binding.progressBar.progress = percent }
                    }
                }
                updateStats()
                binding.statusText.text = "FaceNet-512 محفوظ محليًا بشكل دائم. سيتم استخدامه بعد تفعيل مسار المطابقة متعدد النماذج."
                toast("تم تثبيت FaceNet-512 بنجاح")
            } catch (e: Exception) {
                binding.statusText.text = "فشل FaceNet-512: ${e.message}"
                toast("فشل استيراد FaceNet-512")
            } finally { setBusy(false, "SYSTEM READY") }
        }
    }

    private fun setBusy(busy: Boolean, status: String) {
        binding.progressBar.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.progressBar.progress = 0
        binding.statusText.text = status
        binding.exportBackupButton.isEnabled = !busy
        binding.importBackupButton.isEnabled = !busy
        binding.importModelButton.isEnabled = !busy
        binding.removeModelButton.isEnabled = !busy
        binding.importFaceNetModelButton.isEnabled = !busy
        binding.removeFaceNetModelButton.isEnabled = !busy
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) { value /= 1024.0; index++ }
        return "${DecimalFormat("0.0").format(value)} ${units[index]}"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private data class Stats(val images: Int, val faces: Long, val persons: Long, val embeddings: Long)
}
