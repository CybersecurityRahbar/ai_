package com.example.personalmemoryai.indexing

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
import kotlinx.coroutines.tasks.await
import kotlin.math.max

/** Structured OCR evidence. Quality is a diagnostic signal, not an identity confidence. */
data class OcrResult(
    val text: String,
    val language: String,
    val qualityScore: Float,
    val latinCharacters: Int,
    val arabicCharacters: Int,
    val passCount: Int,
    val successfulPasses: Int
)

class OcrEngine(private val context: Context) {
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val arabicRecognizer = ArabicOcrEngine(context)
    private val diagnostics = DiagnosticsManager.get(context)

    suspend fun process(uri: Uri): OcrResult {
        var latinText = ""
        var latinFailed = false
        try {
            latinText = recognizeLatin(uri)
            diagnostics.info("OCR_LATIN", "Latin OCR completed", mapOf("characters" to latinText.length.toString()))
        } catch (e: Exception) {
            latinFailed = true
            diagnostics.warning("OCR_LATIN", "Latin OCR failed; Arabic pipeline will still run", mapOf("error" to (e.message ?: e.javaClass.simpleName)))
        }

        val arabic = try {
            arabicRecognizer.recognizeDetailed(uri).also {
                diagnostics.info("OCR_ARABIC", "Arabic OCR completed", mapOf("characters" to it.text.length.toString(), "quality" to "%.3f".format(java.util.Locale.US, it.qualityScore), "passes" to it.passCount.toString(), "successfulPasses" to it.successfulPasses.toString()))
            }
        } catch (e: Exception) {
            diagnostics.failure("OCR_ARABIC", e)
            ArabicOcrResult("", 0f, 0, 0)
        }

        val combined = combineResults(latinText, arabic.text)
        val arabicChars = combined.count { it in '\u0600'..'\u06FF' }
        val latinChars = combined.count { it in 'A'..'Z' || it in 'a'..'z' }
        val successful = (if (latinText.isNotBlank()) 1 else 0) + arabic.successfulPasses
        val passes = 1 + arabic.passCount
        val evidenceScore = when {
            combined.isBlank() -> 0f
            arabicChars > 0 && latinChars > 0 -> max(arabic.qualityScore, 0.65f)
            arabicChars > 0 -> arabic.qualityScore
            latinChars > 0 -> if (latinFailed) 0.45f else 0.70f
            else -> 0.25f
        }.coerceIn(0f, 1f)
        if (combined.isBlank()) diagnostics.warning("OCR_RESULT", "OCR produced no text", mapOf("uri" to uri.toString()))
        return OcrResult(combined, detectLanguage(combined), evidenceScore, latinChars, arabicChars, passes, successful)
    }

    private suspend fun recognizeLatin(uri: Uri): String {
        val inputImage = InputImage.fromFilePath(context, uri)
        return latinRecognizer.process(inputImage).await().text.trim()
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
