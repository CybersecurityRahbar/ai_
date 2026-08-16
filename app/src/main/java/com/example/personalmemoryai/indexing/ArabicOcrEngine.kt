package com.example.personalmemoryai.indexing

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

class ArabicOcrEngine(
    private val context: Context
) {

    private var tess: TessBaseAPI? = null

    private val tessBaseDir: File
        get() = File(
            context.filesDir,
            "tesseract"
        )

    private val tessDataDir: File
        get() = File(
            tessBaseDir,
            "tessdata"
        )

    private val arabicModel: File
        get() = File(
            tessDataDir,
            "ara.traineddata"
        )

    /**
     * تجهيز نموذج Tesseract العربي.
     *
     * النموذج موجود داخل:
     *
     * assets/tessdata/ara.traineddata
     *
     * ويتم نسخه إلى:
     *
     * files/tesseract/tessdata/ara.traineddata
     */
    private fun prepareTessData() {

        if (!tessDataDir.exists()) {
            if (!tessDataDir.mkdirs() &&
                !tessDataDir.exists()
            ) {
                throw IllegalStateException(
                    "Unable to create Tesseract tessdata directory"
                )
            }
        }

        /*
         * إذا كان الملف موجودًا بالفعل وحجمه معقول،
         * لا نعيد نسخه في كل مرة.
         */
        if (
            arabicModel.exists() &&
            arabicModel.length() >= 100_000
        ) {
            return
        }

        if (arabicModel.exists()) {
            arabicModel.delete()
        }

        context.assets
            .open("tessdata/ara.traineddata")
            .use { input ->

                FileOutputStream(
                    arabicModel
                ).use { output ->

                    input.copyTo(output)
                }
            }

        if (
            !arabicModel.exists() ||
            arabicModel.length() < 100_000
        ) {
            throw IllegalStateException(
                "Arabic OCR model is missing or invalid"
            )
        }
    }

    /**
     * إنشاء محرك Tesseract.
     */
    private fun createEngine(): TessBaseAPI {

        prepareTessData()

        val engine =
            TessBaseAPI()

        try {

            val initialized =
                engine.init(
                    tessBaseDir.absolutePath,
                    "ara"
                )

            if (!initialized) {

                engine.recycle()

                throw IllegalStateException(
                    "Tesseract Arabic initialization failed"
                )
            }

            engine.pageSegMode =
                TessBaseAPI.PageSegMode.PSM_AUTO

            return engine

        } catch (t: Throwable) {

            try {
                engine.recycle()
            } catch (_: Throwable) {
            }

            throw t
        }
    }

    /**
     * استخراج النص العربي من الصورة.
     */
    fun recognize(
        bitmap: Bitmap
    ): String {

        if (bitmap.isRecycled) {
            return ""
        }

        try {

            if (tess == null) {
                tess = createEngine()
            }

            val engine =
                tess ?: return ""

            engine.setImage(bitmap)

            return engine.utF8Text
                ?.trim()
                ?: ""

        } catch (t: Throwable) {

            t.printStackTrace()

            /*
             * إذا فشل Tesseract، نتخلص من المحرك
             * حتى نستطيع إعادة إنشائه للصورة التالية.
             */
            try {
                tess?.clear()
                tess?.recycle()
            } catch (_: Throwable) {
            }

            tess = null

            return ""
        }
    }

    /**
     * تحرير موارد Tesseract.
     */
    fun close() {

        try {
            tess?.clear()
        } catch (_: Throwable) {
        }

        try {
            tess?.recycle()
        } catch (_: Throwable) {
        }

        tess = null
    }
}
