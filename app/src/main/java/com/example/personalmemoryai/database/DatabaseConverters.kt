package com.example.personalmemoryai.database

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converters used by Room for data types that SQLite does not
 * represent directly.
 *
 * Embeddings are stored as compact Float32 byte arrays rather
 * than JSON strings to reduce database size and improve I/O.
 */
class DatabaseConverters {

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): ByteArray? {
        if (value == null) return null

        val buffer = ByteBuffer
            .allocate(value.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        value.forEach { buffer.putFloat(it) }

        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(value: ByteArray?): FloatArray? {
        if (value == null) return null
        if (value.isEmpty()) return FloatArray(0)

        require(value.size % Float.SIZE_BYTES == 0) {
            "Invalid Float32 embedding byte array size: ${value.size}"
        }

        val buffer = ByteBuffer
            .wrap(value)
            .order(ByteOrder.LITTLE_ENDIAN)

        val result = FloatArray(value.size / Float.SIZE_BYTES)

        for (index in result.indices) {
            result[index] = buffer.float
        }

        return result
    }

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        if (value == null) return null

        return value
            .map { it.replace("\\", "\\\\").replace("|", "\\|") }
            .joinToString("|")
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()

        val result = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false

        for (character in value) {
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }

                character == '\\' -> {
                    escaped = true
                }

                character == '|' -> {
                    result.add(current.toString())
                    current.clear()
                }

                else -> {
                    current.append(character)
                }
            }
        }

        if (escaped) {
            current.append('\\')
        }

        result.add(current.toString())

        return result
    }
}
