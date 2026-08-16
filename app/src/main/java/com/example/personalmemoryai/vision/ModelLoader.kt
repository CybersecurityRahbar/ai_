package com.example.personalmemoryai.vision

import android.content.Context
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object ModelLoader {

    fun loadMappedModel(
        context: Context,
        fileName: String
    ): MappedByteBuffer {

        val descriptor =
            context.assets.openFd(
                fileName
            )

        FileInputStream(
            descriptor.fileDescriptor
        ).use { inputStream ->

            val channel =
                inputStream.channel

            return channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength
            )
        }
    }
}
