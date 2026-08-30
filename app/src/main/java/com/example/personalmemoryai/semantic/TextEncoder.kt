package com.example.personalmemoryai.semantic

/** Contract for a local text tower that produces a shared 512-D semantic embedding. */
interface TextEncoder : AutoCloseable {
    fun isReady(): Boolean
    fun encode(text: String): FloatArray
}

/** Explicit fallback used when the text tower is not installed. */
class UnavailableTextEncoder : TextEncoder {
    override fun isReady(): Boolean = false

    override fun encode(text: String): FloatArray {
        throw UnsupportedOperationException(
            "Text Encoder is not installed. Import mobileclip_s2_text.tflite first."
        )
    }

    override fun close() = Unit
}
