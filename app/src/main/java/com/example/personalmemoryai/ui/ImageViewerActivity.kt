package com.example.personalmemoryai.ui

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.personalmemoryai.databinding.ActivityImageViewerBinding

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        binding =
            ActivityImageViewerBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        val uriString =
            intent.getStringExtra(EXTRA_URI)

        if (uriString.isNullOrBlank()) {
            finish()
            return
        }

        binding.imageView.setImageURI(
            Uri.parse(uriString)
        )

        binding.closeButton.setOnClickListener {
            finish()
        }
    }

    companion object {

        const val EXTRA_URI =
            "image_uri"
    }
}
