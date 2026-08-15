package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.BitmapFactory
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

    private val latinRecognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    private val arabicRecognizer =
        ArabicOcrEngine(context)

    suspend fun process(uri: Uri): OcrResult {

        val latinText =
            try {
                recognizeLatin(uri)
            } catch (e: Exception) {
                ""
            }

        val arabicText =
            try {
                recognizeArabic(uri)
            } catch (e: Exception) {
                ""
            }

        val combined =
            combineResults(
                latinText,
                arabicText
            )

        return OcrResult(
            text = combined,
            language = detectLanguage(combined)
        )
    }

    private suspend fun recognizeLatin(
        uri: Uri
    ): String {

        val image =
            InputImage.fromFilePath(
                context,
                uri
            )

        val result =
            latinRecognizer
                .process(image)
                .await()

        return result.text.trim()
    }

    private fun recognizeArabic(
        uri: Uri
    ): String {

        val bitmap =
            context.contentResolver
                .openInputStream(uri)
                ?.use {
                    BitmapFactory.decodeStream(it)
                }
                ?: return ""

        return arabicRecognizer
            .recognize(bitmap)
    }

    private fun combineResults(
        latin: String,
        arabic: String
    ): String {

        val parts =
            linkedSetOf<String>()

        if (latin.isNotBlank()) {
            parts.add(latin)
        }

        if (arabic.isNotBlank()) {
            parts.add(arabic)
        }

        return parts.joinToString("\n")
    }

    private fun detectLanguage(
        text: String
    ): String {

        if (text.isBlank()) {
            return "none"
        }

        val arabic =
            text.any {
                it in '\u0600'..'\u06FF'
            }

        val latin =
            text.any {
                it in 'A'..'Z' ||
                it in 'a'..'z'
            }

        return when {
            arabic && latin -> "mixed"
            arabic -> "ar"
            latin -> "en"
            else -> "unknown"
        }
    }

    fun close() {

        latinRecognizer.close()
        arabicRecognizer.close()
    }
}
