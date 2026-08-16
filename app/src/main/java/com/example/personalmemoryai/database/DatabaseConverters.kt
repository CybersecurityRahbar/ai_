package com.example.personalmemoryai.database

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DatabaseConverters {

    @TypeConverter
    fun floatArrayToByteArray(
        values: FloatArray?
    ): ByteArray? {

        if (values == null) {
            return null
        }

        val buffer =
            ByteBuffer.allocate(
                values.size * 4
            )
                .order(
                    ByteOrder.LITTLE_ENDIAN
                )

        values.forEach {
            buffer.putFloat(it)
        }

        return buffer.array()
    }

    @TypeConverter
    fun byteArrayToFloatArray(
        bytes: ByteArray?
    ): FloatArray? {

        if (bytes == null) {
            return null
        }

        require(
            bytes.size % 4 == 0
        ) {
            "Invalid Float32 vector byte size: ${bytes.size}"
        }

        val buffer =
            ByteBuffer.wrap(bytes)
                .order(
                    ByteOrder.LITTLE_ENDIAN
                )

        val result =
            FloatArray(
                bytes.size / 4
            )

        for (index in result.indices) {
            result[index] =
                buffer.float
        }

        return result
    }
}
