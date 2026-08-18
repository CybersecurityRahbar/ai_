package com.example.personalmemoryai.semantic

/**
 * Future text side of the CLIP semantic-search pipeline.
 *
 * The current release intentionally does not ship a Text Encoder. Keeping
 * this contract now lets us add the compatible MobileCLIP-S2 text model later
 * without changing the database or image encoder APIs.
 */
interface TextEncoder : AutoCloseable {
    fun isReady(): Boolean
    fun encode(text: String): FloatArray
}

class UnavailableTextEncoder : TextEncoder {
    override fun isReady(): Boolean = false

    override fun encode(text: String): FloatArray {
        throw UnsupportedOperationException(
            "Text Encoder is not installed yet. Image semantic matching remains available."
        )
    }

    override fun close() = Unit
}
