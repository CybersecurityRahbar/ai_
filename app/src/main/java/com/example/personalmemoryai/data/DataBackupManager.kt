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

/**
 * Portable backup for the local intelligence library.
 *
 * A .pmai archive contains the Room database plus copies of indexed images
 * that are still readable through their stored URI. On restore the images are
 * placed in app-private library storage and the imported database rows are
 * rebound to those new local files, preserving face/person/embedding IDs.
 */
class DataBackupManager(private val context: Context) {

    companion object {
        const val BACKUP_EXTENSION = ".pmai"
        private const val DB_ENTRY = "database/personal_memory.db"
        private const val MANIFEST_ENTRY = "manifest.txt"
        private const val IMAGE_PREFIX = "images/"
        private const val LIBRARY_DIR = "library/images"
    }

    suspend fun exportBackup(destination: Uri, progress: (Int) -> Unit = {}): ExportResult =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val images = db.imageDao().getAll()
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
            AppDatabase.closeDatabase()

            val dbFile = context.getDatabasePath("personal_memory.db")
            var copied = 0
            val missing = mutableListOf<Long>()
            val resolver = context.contentResolver

            resolver.openOutputStream(destination)?.use { raw ->
                ZipOutputStream(raw.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(DB_ENTRY))
                    FileInputStream(dbFile).use { it.copyTo(zip) }
                    zip.closeEntry()

                    val manifest = buildString {
                        appendLine("format=PersonalMemoryAI-1")
                        appendLine("database=personal_memory.db")
                        appendLine("imageCount=${images.size}")
                    }
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    images.forEach { image ->
                        val entryName = "$IMAGE_PREFIX${image.id}_${safeName(image.fileName)}"
                        val input = openUri(image.uri)
                        if (input == null) {
                            missing += image.id
                        } else {
                            input.use { stream ->
                                zip.putNextEntry(ZipEntry(entryName))
                                stream.copyTo(zip)
                                zip.closeEntry()
                                copied++
                            }
                        }
                        progress(((copied + missing.size) * 100 / images.size.coerceAtLeast(1)))
                    }
                }
            } ?: error("تعذر إنشاء ملف النسخة الاحتياطية")

            ExportResult(images.size, copied, missing)
        }

    suspend fun importBackup(source: Uri, progress: (Int) -> Unit = {}): ImportResult =
        withContext(Dispatchers.IO) {
            val tempRoot = File(context.cacheDir, "pmai_restore_${System.currentTimeMillis()}")
            tempRoot.mkdirs()
            try {
                val archive = File(tempRoot, "backup.pmai")
                context.contentResolver.openInputStream(source)?.use { input ->
                    FileOutputStream(archive).use { input.copyTo(it) }
                } ?: error("تعذر فتح ملف النسخة الاحتياطية")

                ZipFile(archive).use { zip ->
                    require(zip.getEntry(DB_ENTRY) != null) { "النسخة لا تحتوي على personal_memory.db" }
                    require(zip.getEntry(MANIFEST_ENTRY) != null) { "ملف النسخة غير صالح" }

                    val stagedDb = File(tempRoot, "personal_memory.db")
                    zip.getInputStream(zip.getEntry(DB_ENTRY)).use { input ->
                        FileOutputStream(stagedDb).use { input.copyTo(it) }
                    }

                    val currentDb = context.getDatabasePath("personal_memory.db")
                    AppDatabase.closeDatabase()
                    currentDb.parentFile?.mkdirs()
                    FileOutputStream(currentDb).use { out ->
                        FileInputStream(stagedDb).use { it.copyTo(out) }
                    }
                    File(currentDb.path + "-wal").delete()
                    File(currentDb.path + "-shm").delete()

                    val libraryDir = File(context.filesDir, LIBRARY_DIR)
                    libraryDir.mkdirs()
                    var restored = 0
                    var totalImages = 0
                    val db = AppDatabase.getInstance(context)
                    val imageRows = db.imageDao().getAll().associateBy { it.id }

                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith(IMAGE_PREFIX) }
                        .forEach { entry ->
                            val name = entry.name.removePrefix(IMAGE_PREFIX)
                            val id = name.substringBefore('_').toLongOrNull() ?: return@forEach
                            if (!imageRows.containsKey(id)) return@forEach
                            val target = File(libraryDir, "${id}_${safeName(imageRows.getValue(id).fileName)}")
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { input.copyTo(it) }
                            }
                            db.openHelper.writableDatabase.execSQL(
                                "UPDATE images SET uri = ?, filePath = ? WHERE id = ?",
                                arrayOf(Uri.fromFile(target).toString(), target.absolutePath, id)
                            )
                            restored++
                            totalImages++
                            progress((restored * 100 / imageRows.size.coerceAtLeast(1)))
                        }

                    ImportResult(imageRows.size, restored)
                }
            } finally {
                tempRoot.deleteRecursively()
            }
        }

    private fun openUri(value: String): InputStream? {
        return try {
            val uri = Uri.parse(value)
            if (uri.scheme.equals("file", true)) {
                uri.path?.let(::File)?.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "image" }

    data class ExportResult(val imageCount: Int, val copiedImages: Int, val missingImageIds: List<Long>)
    data class ImportResult(val imageCount: Int, val restoredImages: Int)
}
