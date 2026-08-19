package com.example.personalmemoryai.data

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable .pmai backup containing database, indexed images and metadata. */
class DataBackupManager(private val context: Context) {
    companion object {
        const val BACKUP_EXTENSION = ".pmai"
        private const val DB_ENTRY = "database/personal_memory.db"
        private const val MANIFEST_ENTRY = "manifest.txt"
        private const val IMAGE_PREFIX = "images/"
        private const val LIBRARY_DIR = "library/images"
    }

    suspend fun exportBackup(destination: Uri, progress: (Int) -> Unit = {}): ExportResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val images = db.imageDao().getAll()
        db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        AppDatabase.closeDatabase()

        val dbFile = context.getDatabasePath("personal_memory.db")
        require(dbFile.isFile && dbFile.length() > 0) { "قاعدة البيانات غير موجودة أو فارغة" }
        val resolver = context.contentResolver
        var copied = 0
        val missing = mutableListOf<Long>()

        try {
            resolver.openOutputStream(destination, "w")?.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(DB_ENTRY))
                    FileInputStream(dbFile).use { it.copyTo(zip) }
                    zip.closeEntry()

                    val manifest = buildString {
                        appendLine("format=PersonalMemoryAI-2")
                        appendLine("database=personal_memory.db")
                        appendLine("imageCount=${images.size}")
                        appendLine("createdAt=${System.currentTimeMillis()}")
                    }
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    images.forEachIndexed { index, image ->
                        val entryName = "$IMAGE_PREFIX${image.id}_${safeName(image.fileName)}"
                        openUri(image.uri)?.use { input ->
                            zip.putNextEntry(ZipEntry(entryName))
                            input.copyTo(zip)
                            zip.closeEntry()
                            copied++
                        } ?: missing.add(image.id)
                        progress(((index + 1) * 100 / images.size.coerceAtLeast(1)))
                    }
                }
            } ?: error("تعذر فتح ملف الوجهة للكتابة")
        } finally {
            // Re-open Room on the next operation; do not leave the singleton closed.
            AppDatabase.getInstance(context)
        }
        ExportResult(images.size, copied, missing)
    }

    suspend fun importBackup(source: Uri, progress: (Int) -> Unit = {}): ImportResult = withContext(Dispatchers.IO) {
        val tempRoot = File(context.cacheDir, "pmai_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val archive = File(tempRoot, "backup.pmai")
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(archive).use { input.copyTo(it) }
            } ?: error("تعذر فتح ملف النسخة الاحتياطية")

            ZipFile(archive).use { zip ->
                require(zip.getEntry(DB_ENTRY) != null) { "النسخة لا تحتوي على personal_memory.db" }
                require(zip.getEntry(MANIFEST_ENTRY) != null) { "ملف النسخة غير صالح" }
                val stagedDb = File(tempRoot, "personal_memory.db")
                zip.getInputStream(zip.getEntry(DB_ENTRY)).use { input -> FileOutputStream(stagedDb).use { input.copyTo(it) } }
                require(stagedDb.isFile && stagedDb.length() > 0) { "قاعدة البيانات داخل النسخة فارغة" }

                AppDatabase.closeDatabase()
                val currentDb = context.getDatabasePath("personal_memory.db")
                currentDb.parentFile?.mkdirs()
                FileOutputStream(currentDb).use { out -> FileInputStream(stagedDb).use { it.copyTo(out) } }
                File(currentDb.path + "-wal").delete()
                File(currentDb.path + "-shm").delete()

                val libraryDir = File(context.filesDir, LIBRARY_DIR).apply { mkdirs() }
                val db = AppDatabase.getInstance(context)
                val imageRows = db.imageDao().getAll().associateBy { it.id }
                var restored = 0
                val imageEntries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(IMAGE_PREFIX) }.toList()
                imageEntries.forEachIndexed { index, entry ->
                    val name = entry.name.removePrefix(IMAGE_PREFIX)
                    val id = name.substringBefore('_').toLongOrNull() ?: return@forEachIndexed
                    val row = imageRows[id] ?: return@forEachIndexed
                    val target = File(libraryDir, "${id}_${safeName(row.fileName)}")
                    zip.getInputStream(entry).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                    require(target.isFile && target.length() > 0) { "فشل استعادة الصورة ${row.fileName}" }
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE images SET uri = ?, filePath = ? WHERE id = ?",
                        arrayOf(Uri.fromFile(target).toString(), target.absolutePath, id)
                    )
                    restored++
                    progress(((index + 1) * 100 / imageEntries.size.coerceAtLeast(1)))
                }
                ImportResult(imageRows.size, restored)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun openUri(value: String): InputStream? = try {
        val uri = Uri.parse(value)
        if (uri.scheme.equals("file", true)) uri.path?.let(::File)?.inputStream()
        else context.contentResolver.openInputStream(uri)
    } catch (_: Exception) { null }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "image" }

    data class ExportResult(val imageCount: Int, val copiedImages: Int, val missingImageIds: List<Long>)
    data class ImportResult(val imageCount: Int, val restoredImages: Int)
}
