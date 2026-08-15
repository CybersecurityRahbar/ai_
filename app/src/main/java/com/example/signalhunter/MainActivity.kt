package com.example.personalmemoryai

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personalmemoryai.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val recognizer by lazy {
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )
    }

    private val indexedImages = mutableListOf<ImageRecord>()

    private val imagePicker =
        registerForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->

            if (uris.isNullOrEmpty()) {
                showStatus("لم يتم اختيار أي صورة")
                return@registerForActivityResult
            }

            val limited = uris.take(100)

            showStatus(
                "تم اختيار ${limited.size} صورة\nبدء الفهرسة..."
            )

            indexImages(limited)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectButton.setOnClickListener {
            imagePicker.launch("image/*")
        }

        binding.searchButton.setOnClickListener {
            performSearch(
                binding.searchEditText.text.toString()
            )
        }

        showStatus(
            "Personal Memory AI\n\n" +
                    "جاهز لفهرسة الصور."
        )
    }

    private fun indexImages(uris: List<Uri>) {

        lifecycleScope.launch {

            binding.progressBar.max = uris.size
            binding.progressBar.progress = 0

            var completed = 0

            for (uri in uris) {

                try {

                    val record = processImage(uri)

                    indexedImages.add(record)

                    completed++

                    withContext(Dispatchers.Main) {

                        binding.progressBar.progress = completed

                        binding.counterText.text =
                            "$completed / ${uris.size}"

                        binding.statusText.text =
                            "فهرسة الصورة $completed من ${uris.size}"
                    }

                } catch (e: Exception) {

                    completed++

                    withContext(Dispatchers.Main) {

                        binding.progressBar.progress = completed

                        binding.statusText.text =
                            "خطأ في صورة $completed: ${e.message}"
                    }
                }
            }

            saveIndex()

            withContext(Dispatchers.Main) {

                binding.statusText.text =
                    "اكتملت الفهرسة\n\n" +
                            "الصور المفهرسة: ${indexedImages.size}\n" +
                            "يمكنك الآن البحث."
            }
        }
    }

    private suspend fun processImage(uri: Uri): ImageRecord {

        val inputImage =
            InputImage.fromFilePath(
                this,
                uri
            )

        val result =
            recognizer
                .process(inputImage)
                .await()

        val text = result.text

        val name = getFileName(uri)

        return ImageRecord(
            uri = uri.toString(),
            name = name,
            ocrText = text
        )
    }

    private fun performSearch(query: String) {

        if (query.isBlank()) {
            showStatus("اكتب شيئًا للبحث.")
            return
        }

        val normalized =
            query.trim().lowercase()

        val results =
            indexedImages.filter { image ->

                image.name.lowercase()
                    .contains(normalized) ||

                image.ocrText.lowercase()
                    .contains(normalized)
            }

        if (results.isEmpty()) {

            showStatus(
                "لم أجد نتائج لـ:\n\n$query"
            )

            return
        }

        val output = StringBuilder()

        output.append(
            "نتائج البحث عن:\n\"$query\"\n\n"
        )

        output.append(
            "عدد النتائج: ${results.size}\n\n"
        )

        results.take(20).forEachIndexed { index, image ->

            output.append(
                "${index + 1}. ${image.name}\n"
            )

            val text =
                image.ocrText
                    .replace("\n", " ")
                    .take(180)

            if (text.isNotBlank()) {

                output.append(
                    "   OCR: $text\n"
                )
            }

            output.append("\n")
        }

        showStatus(output.toString())
    }

    private fun saveIndex() {

        val array = JSONArray()

        indexedImages.forEach { image ->

            val obj = JSONObject()

            obj.put("uri", image.uri)
            obj.put("name", image.name)
            obj.put("ocr", image.ocrText)

            array.put(obj)
        }

        getSharedPreferences(
            "memory_index",
            MODE_PRIVATE
        )
            .edit()
            .putString(
                "images",
                array.toString()
            )
            .apply()
    }

    private fun getFileName(uri: Uri): String {

        var result = "unknown"

        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->

            if (cursor.moveToFirst()) {

                val index =
                    cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                    )

                if (index >= 0) {
                    result = cursor.getString(index)
                }
            }
        }

        return result
    }

    private fun showStatus(text: String) {

        binding.statusText.text = text
    }

    override fun onDestroy() {

        recognizer.close()

        super.onDestroy()
    }
}

data class ImageRecord(
    val uri: String,
    val name: String,
    val ocrText: String
)
