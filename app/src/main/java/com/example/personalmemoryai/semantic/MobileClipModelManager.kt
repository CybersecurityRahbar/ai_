package com.example.personalmemoryai.semantic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns the optional on-device MobileCLIP-S2 FP16 model.
 *
 * The model is intentionally NOT committed to GitHub because it is a large
 * binary. It is downloaded once into app-private storage and reused forever.
 */
class MobileClipModelManager(private val context: Context) {

    companion object {
        const val MODEL_FILE_NAME = "mobileclip_s2_fp16.tflite"
        const val MODEL_VERSION = "mobileclip-s2-fp16-v1"

        // Memoria Mobile Vision Assets: validated MobileCLIP-S2 TFLite FP16 export.
        const val MODEL_URL =
            "https://huggingface.co/youthfedpycharm/memoria-mobile-vision-assets/resolve/main/mobileclip-s2/model.float16.tflite?download=true"
    }

    private val modelDir: File
        get() = File(context.filesDir, "models/semantic")

    val modelFile: File
        get() = File(modelDir, MODEL_FILE_NAME)

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() > 1024L * 1024L

    suspend fun ensureModel(
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext modelFile

        modelDir.mkdirs()
        val temp = File(modelDir, "$MODEL_FILE_NAME.download")
        var connection: HttpURLConnection? = null

        try {
            connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PersonalMemoryAI/1.0")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("MobileCLIP download failed: HTTP $code")
            }

            val total = connection.contentLengthLong
            var downloaded = 0L

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.fd.sync()
                }
            }

            if (temp.length() < 50L * 1024L * 1024L) {
                throw IllegalStateException(
                    "Downloaded MobileCLIP file is unexpectedly small: ${temp.length()} bytes"
                )
            }

            if (modelFile.exists()) modelFile.delete()
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
                temp.delete()
            }

            modelFile
        } finally {
            connection?.disconnect()
            if (temp.exists() && !modelFile.exists()) temp.delete()
        }
    }

    fun deleteModel() {
        if (modelFile.exists()) modelFile.delete()
    }
}
