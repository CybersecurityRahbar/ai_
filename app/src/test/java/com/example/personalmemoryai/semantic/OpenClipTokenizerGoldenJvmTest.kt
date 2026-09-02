package com.example.personalmemoryai.semantic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OpenClipTokenizerGoldenJvmTest {

    @Test
    fun matchesVerifiedAppleTokenIdsForAllGoldenCases() {
        val assets = File("src/main/assets/models/semantic/openclip")
        val vocab = File(assets, "vocab.json")
        val merges = File(assets, "merges.txt")
        check(vocab.isFile && vocab.length() > 0) { "Missing production vocab.json: ${vocab.absolutePath}" }
        check(merges.isFile && merges.length() > 0) { "Missing production merges.txt: ${merges.absolutePath}" }

        val tokenizer = OpenClipTokenizer(
            vocab.readText(Charsets.UTF_8),
            merges.readText(Charsets.UTF_8)
        )

        assertEquals(19, GOLDEN.size)
        GOLDEN.forEach { (text, prefix) ->
            val actual = tokenizer.encode(text)
            val expected = LongArray(OpenClipTokenizer.CONTEXT_LENGTH)
            prefix.copyInto(expected)
            assertArrayEquals("Tokenizer mismatch for: '$text'", expected, actual)
        }
    }

    companion object {
        private const val SOT = OpenClipTokenizer.SOT_ID.toLong()
        private const val EOT = OpenClipTokenizer.EOT_ID.toLong()

        /** Prefixes come from the verified Apple MobileCLIP-S2 Deep Oracle V1 report. */
        private val GOLDEN = linkedMapOf(
            "" to longArrayOf(SOT, EOT),
            "a diagram" to longArrayOf(SOT, 320, 22697, EOT),
            "a dog" to longArrayOf(SOT, 320, 1929, EOT),
            "a cat" to longArrayOf(SOT, 320, 2368, EOT),
            "a landscape" to longArrayOf(SOT, 320, 5727, EOT),
            "a person" to longArrayOf(SOT, 320, 2533, EOT),
            "a screenshot" to longArrayOf(SOT, 320, 12646, EOT),
            "a building" to longArrayOf(SOT, 320, 2307, EOT),
            "two people standing together" to longArrayOf(SOT, 1237, 1047, 2862, 1952, EOT),
            "a red car" to longArrayOf(SOT, 320, 736, 1615, EOT),
            "hello world" to longArrayOf(SOT, 3306, 1002, EOT),
            "A PHOTO OF A PERSON" to longArrayOf(SOT, 320, 1125, 539, 320, 2533, EOT),
            "A photo of a person, smiling." to longArrayOf(SOT, 320, 1125, 539, 320, 2533, 267, 9200, 269, EOT),
            "person wearing glasses" to longArrayOf(SOT, 2533, 3309, 6116, EOT),
            "two people in a room" to longArrayOf(SOT, 1237, 1047, 530, 320, 1530, EOT),
            "Arabic العربية" to longArrayOf(SOT, 16709, 12973, 18843, 10948, 16378, 12046, 20915, EOT),
            "1234567890" to longArrayOf(SOT, 272, 273, 274, 275, 276, 277, 278, 279, 280, 271, EOT),
            "symbols !@#$%^&*()" to longArrayOf(SOT, 25476, 0, 31, 2, 3, 4, 61, 5, 9, 8475, EOT),
            "a,b.c:d;e/f?g!" to longArrayOf(SOT, 320, 267, 321, 269, 322, 281, 323, 282, 324, 270, 325, 286, 326, 256, EOT)
        )
    }
}

// CI trigger: rerun after production tokenizer parser hardening.
