package com.example.personalmemoryai.ui

import android.content.Context
import android.content.Intent
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

        try {

            binding.imageView.setImageURI(
                Uri.parse(uriString)
            )

        } catch (t: Throwable) {

            t.printStackTrace()

            finish()

            return
        }

        binding.closeButton.setOnClickListener {
            finish()
        }
    }

    companion object {

        const val EXTRA_URI =
            "image_uri"

        fun start(
            context: Context,
            uri: String
        ) {

            val intent =
                Intent(
                    context,
                    ImageViewerActivity::class.java
                ).apply {

                    putExtra(
                        EXTRA_URI,
                        uri
                    )

                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

            context.startActivity(intent)
        }
    }
}
