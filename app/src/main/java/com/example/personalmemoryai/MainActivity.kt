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
import com.example.personalmemoryai.databinding.ActivityMainBinding
import com.example.personalmemoryai.indexing.ImageIndexer
import com.example.personalmemoryai.ui.ImageResultAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var database: AppDatabase
    private lateinit var adapter: ImageResultAdapter

    private var indexer: ImageIndexer? = null

    private val imagePicker =
        registerForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->

            if (uris.isNullOrEmpty()) {
                showStatus("لم يتم اختيار أي صورة")
                return@registerForActivityResult
            }

            val limitedUris = uris.take(100)

            showStatus(
                "تم اختيار ${limitedUris.size} صورة\nبدء الفهرسة..."
            )

            indexImages(limitedUris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding =
            ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        database =
            AppDatabase.getInstance(applicationContext)

        indexer =
            ImageIndexer(applicationContext)

        setupRecyclerView()

        binding.selectButton.setOnClickListener {
            imagePicker.launch("image/*")
        }

        binding.searchButton.setOnClickListener {
            performSearch(
                binding.searchEditText.text
                    .toString()
            )
        }

        showStatus(
            "Personal Memory AI\n\nجاهز لفهرسة الصور."
        )
    }

    private fun setupRecyclerView() {

        adapter =
            ImageResultAdapter { image ->

                ImageViewerActivity
                    .start(
                        this,
                        image.uri
                    )
            }

        binding.resultsRecyclerView.layoutManager =
            LinearLayoutManager(this)

        binding.resultsRecyclerView.adapter =
            adapter
    }

    private fun indexImages(
        uris: List<Uri>
    ) {

        lifecycleScope.launch {

            binding.progressBar.visibility =
                android.view.View.VISIBLE

            binding.progressBar.max =
                uris.size

            binding.progressBar.progress =
                0

            var completed = 0
            var failed = 0

            for (uri in uris) {

                try {

                    val entity =
                        withContext(Dispatchers.IO) {
                            indexer?.indexImage(uri)
                        }

                    if (entity != null) {
                        completed++
                    } else {
                        failed++
                    }

                } catch (t: Throwable) {

                    failed++

                    t.printStackTrace()
                }

                completed.coerceAtLeast(0)

                val processed =
                    completed + failed

                binding.progressBar.progress =
                    processed

                binding.counterText.text =
                    "$processed / ${uris.size}"

                binding.statusText.text =
                    "فهرسة الصورة $processed من ${uris.size}\n" +
                    "نجح: $completed | فشل: $failed"
            }

            binding.progressBar.visibility =
                android.view.View.GONE

            loadAllImages()

            binding.statusText.text =
                "اكتملت الفهرسة\n\n" +
                "تمت معالجة: ${uris.size}\n" +
                "نجح: $completed\n" +
                "فشل: $failed"
        }
    }

    private fun performSearch(
        query: String
    ) {

        if (query.isBlank()) {

            showStatus(
                "اكتب شيئًا للبحث."
            )

            return
        }

        lifecycleScope.launch {

            val results =
                withContext(Dispatchers.IO) {

                    database
                        .imageDao()
                        .search(
                            query.trim()
                        )
                }

            adapter.submitList(results)

            binding.counterText.text =
                "النتائج: ${results.size}"

            binding.statusText.text =
                if (results.isEmpty()) {
                    "لم أجد نتائج لـ:\n$query"
                } else {
                    "تم العثور على ${results.size} نتيجة لـ:\n$query"
                }
        }
    }

    private fun loadAllImages() {

        lifecycleScope.launch {

            val images =
                withContext(Dispatchers.IO) {

                    database
                        .imageDao()
                        .getAll()
                }

            adapter.submitList(images)

            binding.counterText.text =
                "الفهرس: ${images.size} صورة"
        }
    }

    private fun showStatus(
        text: String
    ) {
        binding.statusText.text = text
    }

    override fun onDestroy() {

        indexer?.close()

        super.onDestroy()
    }
}
