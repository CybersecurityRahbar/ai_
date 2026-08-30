package com.example.personalmemoryai.semantic

import android.content.Context
import android.text.Html
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of the OpenAI/OpenCLIP CLIP tokenizer used by
 * Apple's MobileCLIP-S2 reference.
 *
 * Production assets are the pinned Apple/OpenCLIP vocab.json + merges.txt.
 * The third-party plainhub tokenizer.json is deliberately not used because
 * the differential audit proved that its serialized execution path diverges.
 */
class OpenClipTokenizer(private val context: Context) {
    companion object {
        const val CONTEXT_LENGTH = 77
        const val SOT_TOKEN = "<start_of_text>"
        const val EOT_TOKEN = "<end_of_text>"
        const val SOT_ID = 49406
        const val EOT_ID = 49407
        const val VOCAB_SIZE = 49408
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
        val vocabJson = context.assets.open(VOCAB_ASSET).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
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
                    if (parts.size == 2) {
                        ranks[parts[0] to parts[1]] = rank++
                    }
                }
            }
        }
        bpeRanks = ranks
        byteEncoder = bytesToUnicode()

        require(encoder.size == VOCAB_SIZE) {
            "Unexpected CLIP vocabulary size: ${encoder.size}"
        }
        require(encoder[SOT_TOKEN] == SOT_ID) { "Unexpected SOT id" }
        require(encoder[EOT_TOKEN] == EOT_ID) { "Unexpected EOT id" }
    }

    /** Returns exactly 77 INT64-compatible CLIP token ids, including SOT/EOT. */
    fun encode(text: String): LongArray {
        val cleaned = normalizeForClip(text)
        val ids = ArrayList<Int>(CONTEXT_LENGTH)
        ids += SOT_ID

        for (piece in tokenPattern.findAll(cleaned)) {
            when (piece.value.lowercase(Locale.US)) {
                SOT_TOKEN -> {
                    if (ids.size < CONTEXT_LENGTH - 1) ids += SOT_ID
                    continue
                }
                EOT_TOKEN -> {
                    if (ids.size < CONTEXT_LENGTH - 1) ids += EOT_ID
                    continue
                }
            }

            val bytes = piece.value.toByteArray(Charsets.UTF_8)
            val mapped = buildString(bytes.size) {
                for (b in bytes) append(byteEncoder[b.toInt() and 0xFF])
            }
            val bpePieces = bpe(mapped).split(' ')
            for (symbol in bpePieces) {
                val id = encoder[symbol]
                    ?: throw IllegalStateException("Missing CLIP vocabulary token: $symbol")
                if (ids.size >= CONTEXT_LENGTH - 1) break
                ids += id
            }
            if (ids.size >= CONTEXT_LENGTH - 1) break
        }

        ids += EOT_ID
        val out = LongArray(CONTEXT_LENGTH)
        val count = minOf(ids.size, CONTEXT_LENGTH)
        for (i in 0 until count) out[i] = ids[i].toLong()
        if (ids.size > CONTEXT_LENGTH) out[CONTEXT_LENGTH - 1] = EOT_ID.toLong()
        return out
    }

    private fun normalizeForClip(text: String): String {
        // Match the relevant OpenCLIP cleaning sequence: HTML unescape +
        // whitespace normalization + trimming + lowercase. NFC keeps already
        // normalized Unicode stable without introducing compatibility folds.
        var cleaned = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
        cleaned = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return Normalizer.normalize(cleaned, Normalizer.Form.NFC).lowercase(Locale.US)
    }

    private fun bpe(token: String): String {
        cache[token]?.let { return it }
        if (token.isEmpty()) return ""

        var current = token.dropLast(1).map { it.toString() }.toMutableList()
        current += token.last().toString() + "</w>"
        if (current.size == 1) {
            return current[0].also { cache[token] = it }
        }

        while (true) {
            var bestFirst = ""
            var bestSecond = ""
            var bestRank = Int.MAX_VALUE
            for (i in 0 until current.size - 1) {
                val first = current[i]
                val second = current[i + 1]
                val rank = bpeRanks[first to second] ?: Int.MAX_VALUE
                if (rank < bestRank) {
                    bestRank = rank
                    bestFirst = first
                    bestSecond = second
                }
            }

            if (bestRank == Int.MAX_VALUE) break

            val merged = ArrayList<String>(current.size)
            var i = 0
            while (i < current.size) {
                if (
                    i < current.size - 1 &&
                    current[i] == bestFirst &&
                    current[i + 1] == bestSecond
                ) {
                    merged += bestFirst + bestSecond
                    i += 2
                } else {
                    merged += current[i]
                    i++
                }
            }
            current = merged
            if (current.size == 1) break
        }

        return current.joinToString(" ").also { cache[token] = it }
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
