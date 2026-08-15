package com.example.personalmemoryai

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.databinding.ActivityMainBinding
import com.example.personalmemoryai.indexing.ImageIndexer
import com.example.personalmemoryai.ui.ImageResultAdapter
import com.example.personalmemoryai.ui.ImageViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var database: AppDatabase

    private lateinit var indexer: ImageIndexer

    private lateinit var adapter: ImageResultAdapter

    private val imagePicker =
        registerForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->

            if (uris.isNullOrEmpty()) {
                return@registerForActivityResult
            }

            indexImages(uris.take(100))
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        database =
            AppDatabase.getInstance(this)

        indexer =
            ImageIndexer(this)

        adapter =
            ImageResultAdapter { image ->

                val intent =
                    Intent(
                        this,
                        ImageViewerActivity::class.java
                    )

                intent.putExtra(
                    ImageViewerActivity.EXTRA_URI,
                    image.uri
                )

                startActivity(intent)
            }

        binding.resultsRecyclerView.layoutManager =
            LinearLayoutManager(this)

        binding.resultsRecyclerView.adapter =
            adapter

        binding.selectButton.setOnClickListener {

            imagePicker.launch("image/*")
        }

        binding.searchButton.setOnClickListener {

            performSearch()
        }

        updateCounter()
    }

    private fun indexImages(
        uris: List<android.net.Uri>
    ) {

        binding.progressBar.visibility =
            android.view.View.VISIBLE

        binding.selectButton.isEnabled =
            false

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            var processed = 0

            for (uri in uris) {

                processed++

                val result =
                    indexer.indexImage(uri)

                withContext(
                    Dispatchers.Main
                ) {

                    binding.statusText.text =
                        "فهرسة $processed / ${uris.size}"

                }
            }

            val count =
                database.imageDao().count()

            withContext(
                Dispatchers.Main
            ) {

                binding.progressBar.visibility =
                    android.view.View.GONE

                binding.selectButton.isEnabled =
                    true

                binding.counterText.text =
                    "الفهرس: $count صورة"

                binding.statusText.text =
                    "اكتملت الفهرسة"

            }
        }
    }

    private fun performSearch() {

        val query =
            binding.searchEditText
                .text
                .toString()
                .trim()

        if (query.isBlank()) {

            lifecycleScope.launch(
                Dispatchers.IO
            ) {

                val all =
                    database.imageDao()
                        .getAll()

                withContext(
                    Dispatchers.Main
                ) {

                    adapter.submitList(all)
                }
            }

            return
        }

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            val results =
                database.imageDao()
                    .searchText(query)

            withContext(
                Dispatchers.Main
            ) {

                adapter.submitList(results)

                binding.statusText.text =
                    "عدد النتائج: ${results.size}"
            }
        }
    }

    private fun updateCounter() {

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            val count =
                database.imageDao()
                    .count()

            withContext(
                Dispatchers.Main
            ) {

                binding.counterText.text =
                    "الفهرس: $count صورة"
            }
        }
    }

    override fun onDestroy() {

        indexer.close()

        super.onDestroy()
    }
}
