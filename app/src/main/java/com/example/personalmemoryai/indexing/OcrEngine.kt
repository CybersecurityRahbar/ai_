package com.example.personalmemoryai.indexing

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class OcrResult(
    val text: String,
    val language: String
)

class OcrEngine(
    private val context: Context
) {

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    suspend fun process(uri: Uri): OcrResult {

        val image = InputImage.fromFilePath(
            context,
            uri
        )

        val result = recognizer.process(image).await()

        val text = result.text.trim()

        return OcrResult(
            text = text,
            language = detectLanguage(text)
        )
    }

    private fun detectLanguage(text: String): String {

        if (text.isBlank()) {
            return "none"
        }

        val hasArabic = text.any { char ->
            char in '\u0600'..'\u06FF'
        }

        val hasLatin = text.any { char ->
            char in 'A'..'Z' || char in 'a'..'z'
        }

        return when {
            hasArabic && hasLatin -> "mixed"
            hasArabic -> "ar"
            hasLatin -> "en"
            else -> "unknown"
        }
    }

    fun close() {
        recognizer.close()
    }
}
