package com.example.personalmemoryai.semantic

import android.content.Context
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
 *
 * The String-based constructor makes the tokenizer independently testable on
 * the JVM without an Android emulator; the production constructor still reads
 * the exact same packaged assets from Android AssetManager.
 */
class OpenClipTokenizer private constructor(
    private val assetContentLoader: () -> Pair<String, String>
) {
    companion object {
        const val CONTEXT_LENGTH = 77
        const val SOT_TOKEN = "<|startoftext|>"
        const val EOT_TOKEN = "<|endoftext|>"
        const val SOT_ID = 49406
        const val EOT_ID = 49407
        const val VOCAB_SIZE = 49408
        const val VOCAB_ASSET = "models/semantic/openclip/vocab.json"
        const val MERGES_ASSET = "models/semantic/openclip/merges.txt"
    }

    constructor(context: Context) : this({
        context.assets.open(VOCAB_ASSET).use { vocab ->
            context.assets.open(MERGES_ASSET).use { merges ->
                vocab.readBytes().toString(Charsets.UTF_8) to
                    merges.readBytes().toString(Charsets.UTF_8)
            }
        }
    })

    constructor(vocabJson: String, mergesText: String) : this({ vocabJson to mergesText })

    private val encoder: Map<String, Int>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val cache = ConcurrentHashMap<String, String>()
    private val byteEncoder: Map<Int, Char>

    /** Exact OpenAI/OpenCLIP SimpleTokenizer token pattern. */
    private val tokenPattern = Regex(
        "'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
        RegexOption.IGNORE_CASE
    )

    init {
        val (vocabJson, mergesText) = assetContentLoader()
        encoder = parseFlatJsonIntMap(vocabJson)

        val ranks = HashMap<Pair<String, String>, Int>(48896)
        BufferedReader(InputStreamReader(mergesText.byteInputStream(), Charsets.UTF_8)).useLines { lines ->
            var rank = 0
            lines.forEach { line ->
                if (line.isBlank() || line.startsWith("#version:")) return@forEach
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 2) ranks[parts[0] to parts[1]] = rank++
            }
        }
        bpeRanks = ranks
        byteEncoder = bytesToUnicode()

        require(encoder.size == VOCAB_SIZE) {
            "Unexpected CLIP vocabulary size: ${encoder.size}"
        }
        require(encoder[SOT_TOKEN] == SOT_ID) {
            "Unexpected SOT id: ${encoder[SOT_TOKEN]} for $SOT_TOKEN"
        }
        require(encoder[EOT_TOKEN] == EOT_ID) {
            "Unexpected EOT id: ${encoder[EOT_TOKEN]} for $EOT_TOKEN"
        }
    }

    /** Returns exactly 77 INT64-compatible CLIP token ids, including SOT/EOT. */
    fun encode(text: String): LongArray {
        val cleaned = normalizeForClip(text)
        val ids = ArrayList<Int>(CONTEXT_LENGTH)
        ids += SOT_ID

        for (piece in tokenPattern.findAll(cleaned)) {
            val lowered = piece.value.lowercase(Locale.US)
            val bytes = lowered.toByteArray(Charsets.UTF_8)
            val mapped = buildString(bytes.size) {
                for (b in bytes) append(byteEncoder[b.toInt() and 0xFF])
            }
            for (symbol in bpe(mapped).split(' ')) {
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
        var cleaned = decodeHtmlEntities(text)
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return Normalizer.normalize(cleaned, Normalizer.Form.NFC).lowercase(Locale.US)
    }

    /** Small JVM-safe HTML entity decoder for entity forms commonly seen in OCR/text input. */
    private fun decodeHtmlEntities(text: String): String {
        return Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);?").replace(text) { m ->
            when {
                m.value.equals("&amp;", true) -> "&"
                m.value.equals("&lt;", true) -> "<"
                m.value.equals("&gt;", true) -> ">"
                m.value.equals("&quot;", true) -> "\""
                m.value.equals("&apos;", true) -> "'"
                m.value.equals("&nbsp;", true) -> " "
                m.groupValues[1].startsWith("#x", true) ->
                    m.groupValues[1].substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                m.groupValues[1].startsWith("#") ->
                    m.groupValues[1].substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> m.value
            }
        }
    }

    private fun parseFlatJsonIntMap(json: String): Map<String, Int> {
        val result = HashMap<String, Int>(VOCAB_SIZE)
        var index = 0

        fun skipWhitespace() {
            while (index < json.length && json[index].isWhitespace()) index++
        }

        fun expect(ch: Char) {
            skipWhitespace()
            require(index < json.length && json[index] == ch) {
                "Invalid vocabulary JSON: expected '$ch' at offset $index"
            }
            index++
        }

        fun parseString(): String {
            skipWhitespace()
            require(index < json.length && json[index] == '"') {
                "Invalid vocabulary JSON string at offset $index"
            }
            index++
            val out = StringBuilder()
            while (index < json.length) {
                val ch = json[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < json.length) { "Invalid vocabulary JSON escape" }
                        when (val esc = json[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= json.length) { "Invalid unicode escape" }
                                val hex = json.substring(index, index + 4)
                                out.append(hex.toIntOrNull(16)?.toChar() ?: error("Invalid unicode escape: $hex"))
                                index += 4
                            }
                            else -> error("Unsupported vocabulary JSON escape: \\$esc")
                        }
                    }
                    else -> out.append(ch)
                }
            }
            error("Unterminated vocabulary JSON string")
        }

        fun parseInt(): Int {
            skipWhitespace()
            val start = index
            if (index < json.length && (json[index] == '-' || json[index] == '+')) index++
            while (index < json.length && json[index].isDigit()) index++
            require(index > start) { "Invalid vocabulary JSON integer at offset $start" }
            return json.substring(start, index).toIntOrNull()
                ?: error("Vocabulary integer out of range at offset $start")
        }

        expect('{')
        skipWhitespace()
        if (index < json.length && json[index] == '}') {
            index++
            return result
        }

        while (true) {
            val key = parseString()
            expect(':')
            result[key] = parseInt()
            skipWhitespace()
            require(index < json.length) { "Unexpected end of vocabulary JSON" }
            when (json[index++]) {
                ',' -> continue
                '}' -> break
                else -> error("Invalid vocabulary JSON separator at offset ${index - 1}")
            }
        }
        skipWhitespace()
        require(index == json.length) { "Trailing data after vocabulary JSON at offset $index" }
        return result
    }

    private fun bpe(token: String): String {
        cache[token]?.let { return it }
        if (token.isEmpty()) return ""

        var current = token.dropLast(1).map { it.toString() }.toMutableList()
        current += token.last().toString() + "</w>"
        if (current.size == 1) return current[0].also { cache[token] = it }

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
