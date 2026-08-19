package com.example.personalmemoryai.indexing

import android.content.Context
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.database.ImageEntity
import kotlin.math.max
import kotlin.math.min

/**
 * Evidence-aware OCR search. OCR is treated as probabilistic evidence rather than ground truth.
 * Arabic normalization plus bounded edit-distance matching reduces false negatives caused by
 * common OCR substitutions while keeping exact matches dominant.
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
        val textTokens = text.split(' ').filter { it.length >= 2 }

        var tokenHits = 0
        var fuzzyTokenScore = 0f
        for (token in tokens) {
            if (text.contains(token)) {
                tokenHits++
                fuzzyTokenScore += 1f
                continue
            }
            val best = textTokens.maxOfOrNull { similarity(token, it) } ?: 0f
            if (best >= 0.72f) tokenHits++
            fuzzyTokenScore += best
        }
        val tokenScore = if (tokens.isEmpty()) 0f else tokenHits.toFloat() / tokens.size
        val fuzzyScore = if (tokens.isEmpty()) 0f else fuzzyTokenScore / tokens.size

        val nonSpaceQuery = query.filterNot { it.isWhitespace() }
        val matchedCharacters = nonSpaceQuery.count { text.contains(it) }
        val charCoverage = matchedCharacters.toFloat() / nonSpaceQuery.length.coerceAtLeast(1)
        val quality = image.ocrQualityScore.coerceIn(0f, 1f)

        val relevance = (exact * 0.50f + tokenScore * 0.20f + fuzzyScore * 0.20f + charCoverage * 0.10f)
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
            if (fuzzyScore > tokenScore && tokens.isNotEmpty()) append("fuzzy OCR match; ")
            append("OCR quality=")
            append("%.2f".format(java.util.Locale.US, quality))
        }.trimEnd(';', ' ')

        return OcrSearchResult(image, adjusted, quality, min(matchedCharacters, nonSpaceQuery.length), band, reason)
    }

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isBlank() || b.isBlank()) return 0f
        val distance = levenshtein(a, b)
        val longest = max(a.length, b.length)
        return (1f - distance.toFloat() / longest).coerceIn(0f, 1f)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val insertion = current[j] + 1
                val deletion = previous[j + 1] + 1
                val substitution = previous[j] + if (a[i] == b[j]) 0 else 1
                current[j + 1] = min(insertion, min(deletion, substitution))
            }
            previous = current
        }
        return previous[b.length]
    }

    companion object {
        /** Arabic-aware normalization reduces false negatives without changing stored evidence. */
        fun normalize(value: String): String = value
            .replace('\u0640'.toString(), "")
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace(Regex("[\\u0622\\u0623\\u0625]"), "ا")
            .replace('\u0649', '\u064A')
            .replace('ة', 'ه')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }
}
