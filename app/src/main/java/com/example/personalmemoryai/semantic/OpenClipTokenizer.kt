package com.example.personalmemoryai.semantic

import android.content.Context
import android.text.Html
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.text.Normalizer

/**
 * Android implementation of the OpenAI/OpenCLIP CLIP tokenizer used by
 * Apple's MobileCLIP-S2 reference. Data files are prepared at build time
 * from Apple's pinned MobileCLIP-S2-OpenCLIP repository.
 */
class OpenClipTokenizer(private val context: Context) {
    companion object {
        const val CONTEXT_LENGTH = 77
        const val SOT_TOKEN = "<start_of_text>"
        const val EOT_TOKEN = "<end_of_text>"
        const val SOT_ID = 49406
        const val EOT_ID = 49407
        const val VOCAB_ASSET = "models/semantic/openclip/vocab.json"
        const val MERGES_ASSET = "models/semantic/openclip/merges.txt"
    }

    private val encoder: Map<String, Int>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val cache = ConcurrentHashMap<String, String>()
    private val byteEncoder: Map<Int, Char>
    private val tokenPattern = Regex(
        "<start_of_text>|<end_of_text>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
        RegexOption.IGNORE_CASE
    )

    init {
        val vocabJson = context.assets.open(VOCAB_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val json = JSONObject(vocabJson)
        val map = HashMap<String, Int>(json.length())
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = json.getInt(key)
        }
        encoder = map

        val ranks = HashMap<Pair<String, String>, Int>(48896)
        context.assets.open(MERGES_ASSET).use { raw ->
            BufferedReader(InputStreamReader(raw, Charsets.UTF_8)).useLines { lines ->
                var rank = 0
                lines.forEach { line ->
                    if (line.isBlank() || line.startsWith("#version:")) return@forEach
                    val parts = line.trim().split(' ')
                    if (parts.size == 2) ranks[parts[0] to parts[1]] = rank++
                }
            }
        }
        bpeRanks = ranks
        byteEncoder = bytesToUnicode()
        require(encoder.size == 49408) { "Unexpected CLIP vocabulary size: ${encoder.size}" }
        require(encoder[SOT_TOKEN] == SOT_ID) { "Unexpected SOT id" }
        require(encoder[EOT_TOKEN] == EOT_ID) { "Unexpected EOT id" }
    }

    fun encode(text: String): LongArray {
        val cleaned = normalizeForClip(text)
        val ids = ArrayList<Int>(CONTEXT_LENGTH)
        ids += SOT_ID
        for (piece in tokenPattern.findAll(cleaned)) {
            val bytes = piece.value.lowercase(Locale.US).toByteArray(Charsets.UTF_8)
            val mapped = buildString(bytes.size) { for (b in bytes) append(byteEncoder[b.toInt() and 0xFF]) }
            val bpe = bpe(mapped)
            for (symbol in bpe.split(' ')) {
                val id = encoder[symbol] ?: throw IllegalStateException("Missing CLIP vocabulary token: $symbol")
                if (ids.size >= CONTEXT_LENGTH - 1) break
                ids += id
            }
            if (ids.size >= CONTEXT_LENGTH - 1) break
        }
        ids += EOT_ID
        val out = LongArray(CONTEXT_LENGTH)
        for (i in ids.indices.take(CONTEXT_LENGTH)) out[i] = ids[i].toLong()
        if (ids.size > CONTEXT_LENGTH) out[CONTEXT_LENGTH - 1] = EOT_ID.toLong()
        return out
    }

    private fun normalizeForClip(text: String): String {
        val cleaned = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
        val fixedWhitespace = cleaned.replace(Regex("\\s+"), " ").trim()
        return Normalizer.normalize(fixedWhitespace, Normalizer.Form.NFC).lowercase(Locale.US)
    }

    private fun bpe(token: String): String {
        cache[token]?.let { return it }
        if (token.isEmpty()) return ""
        val chars = token.toMutableList()
        if (chars.isEmpty()) return ""
        val word = chars.dropLast(1).map { it.toString() }.toMutableList().also { it += chars.last().toString() + "</w>" }
        if (word.size == 1) return word[0].also { cache[token] = it }

        var current = word
        while (current.size > 1) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until current.size - 1) {
                val pair = current[i] to current[i + 1]
                val rank = bpeRanks[pair] ?: Int.MAX_VALUE
                if (rank < bestRank) {
                    bestRank = rank
                    best = pair
                }
            }
            val pair = best ?: break
            if (bestRank == Int.MAX_VALUE) break
            val merged = ArrayList<String>(current.size)
            var i = 0
            while (i < current.size) {
                if (i < current.size - 1 && current[i] == pair.first && current[i + 1] == pair.second) {
                    merged += pair.first + pair.second
                    i += 2
                } else {
                    merged += current[i]
                    i++
                }
            }
            current = merged
        }
        val result = current.joinToString(" ")
        cache[token] = result
        return result
    }

    private fun bytesToUnicode(): Map<Int, Char> {
        val bs = ArrayList<Int>(256)
        for (i in 33..126) bs += i
        for (i in 161..172) bs += i
        for (i in 174..255) bs += i
        val cs = ArrayList<Int>(256)
        cs.addAll(bs)
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs += b
                cs += 256 + n
                n++
            }
        }
        return bs.zip(cs).associate { (b, c) -> b to c.toChar() }
    }
}
