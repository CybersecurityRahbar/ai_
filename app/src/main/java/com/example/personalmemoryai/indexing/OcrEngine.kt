package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max

data class OcrResult(val text: String, val language: String)

class OcrEngine(private val context: Context) {
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val arabicRecognizer = ArabicOcrEngine(context)

    suspend fun process(uri: Uri): OcrResult {
        val latinText = try { recognizeLatin(uri) } catch (e: Exception) { e.printStackTrace(); "" }
        val arabicText = try { recognizeArabic(uri) } catch (e: Exception) { e.printStackTrace(); "" }
        val combined = combineResults(latinText, arabicText)
        return OcrResult(combined, detectLanguage(combined))
    }

    private suspend fun recognizeLatin(uri: Uri): String {
        val inputImage = InputImage.fromFilePath(context, uri)
        return latinRecognizer.process(inputImage).await().text.trim()
    }

    private fun recognizeArabic(uri: Uri): String {
        val bitmap = decodeSampledBitmap(uri, 2400) ?: return ""
        return try { arabicRecognizer.recognize(bitmap) } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private fun decodeSampledBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun combineResults(latin: String, arabic: String): String {
        val parts = linkedSetOf<String>()
        if (latin.isNotBlank()) parts.add(latin)
        if (arabic.isNotBlank()) parts.add(arabic)
        return parts.joinToString("\n")
    }

    private fun detectLanguage(text: String): String {
        if (text.isBlank()) return "none"
        val hasArabic = text.any { it in '\u0600'..'\u06FF' }
        val hasLatin = text.any { it in 'A'..'Z' || it in 'a'..'z' }
        return when { hasArabic && hasLatin -> "mixed"; hasArabic -> "ar"; hasLatin -> "en"; else -> "unknown" }
    }

    fun close() { latinRecognizer.close(); arabicRecognizer.close() }
}
