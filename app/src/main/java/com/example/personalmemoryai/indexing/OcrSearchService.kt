package com.example.personalmemoryai.indexing

import android.content.Context
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import kotlin.math.min

/**
 * Evidence-aware OCR search. It never treats OCR text as ground truth: every hit carries
 * extraction quality, language evidence and a deterministic relevance score.
 */
data class OcrSearchResult(
    val image: ImageEntity,
    val relevanceScore: Float,
    val qualityScore: Float,
    val matchedCharacters: Int,
    val confidenceBand: String,
    val reason: String
)

class OcrSearchService(context: Context) {
    private val dao = AppDatabase.getInstance(context).imageDao()

    suspend fun search(query: String, limit: Int = 100): List<OcrSearchResult> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return emptyList()

        return dao.getAll()
            .asSequence()
            .mapNotNull { image -> score(image, normalizedQuery) }
            .sortedWith(compareByDescending<OcrSearchResult> { it.relevanceScore }
                .thenByDescending { it.qualityScore }
                .thenByDescending { it.matchedCharacters })
            .take(limit.coerceIn(1, 500))
            .toList()
    }

    private fun score(image: ImageEntity, query: String): OcrSearchResult? {
        val text = normalize(image.ocrText)
        if (text.isBlank()) return null

        val exact = if (text.contains(query)) 1f else 0f
        val tokens = query.split(' ').filter { it.length >= 2 }
        val tokenHits = if (tokens.isEmpty()) 0 else tokens.count { text.contains(it) }
        val tokenScore = if (tokens.isEmpty()) 0f else tokenHits.toFloat() / tokens.size
        val characterHits = query.count { it != ' ' && text.contains(it) }
        val charCoverage = characterHits.toFloat() / query.count { it != ' ' }.coerceAtLeast(1)
        val quality = image.ocrQualityScore.coerceIn(0f, 1f)

        val relevance = (exact * 0.55f + tokenScore * 0.30f + charCoverage * 0.15f)
            .coerceIn(0f, 1f)
        if (relevance <= 0f) return null

        val adjusted = (relevance * (0.70f + quality * 0.30f)).coerceIn(0f, 1f)
        val band = when {
            adjusted >= 0.85f && quality >= 0.65f -> "VERY_HIGH"
            adjusted >= 0.70f && quality >= 0.45f -> "HIGH"
            adjusted >= 0.45f -> "MEDIUM"
            else -> "LOW"
        }
        val reason = buildString {
            if (exact > 0f) append("exact phrase; ")
            if (tokenHits > 0) append("$tokenHits/${tokens.size.coerceAtLeast(1)} tokens; ")
            append("OCR quality=")
            append("%.2f".format(java.util.Locale.US, quality))
        }.trimEnd(';', ' ')

        return OcrSearchResult(image, adjusted, quality, min(characterHits, query.length), band, reason)
    }

    companion object {
        /** Arabic-aware normalization reduces false negatives without changing stored evidence. */
        fun normalize(value: String): String = value
            .replace('\u0640'.toString(), "")
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace(Regex("[\\u0622\\u0623\\u0625]"), "ا")
            .replace('\u0649', '\u064A')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }
}
