package com.example.personalmemoryai.data

import android.content.Context
import android.net.Uri
import com.example.personalmemoryai.database.AppDatabase
import com.example.personalmemoryai.diagnostics.DiagnosticsManager
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
    private val diagnostics = DiagnosticsManager.get(context)

    suspend fun exportBackup(destination: Uri, progress: (Int) -> Unit = {}): ExportResult = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("BACKUP_EXPORT", mapOf("destination" to destination.toString()))
        try {
            val db = AppDatabase.getInstance(context)
            val images = db.imageDao().getAll()
            run.stage("CHECKPOINT", "Checkpointing SQLite WAL before snapshot")
            // PRAGMA wal_checkpoint returns rows; executing it with execSQL can fail on Android.
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor -> while (cursor.moveToNext()) { } }
            AppDatabase.closeDatabase()

            val dbFile = context.getDatabasePath("personal_memory.db")
            require(dbFile.isFile && dbFile.length() > 0) { "قاعدة البيانات غير موجودة أو فارغة" }
            run.stage("ARCHIVE", "Writing database and indexed images", mapOf("images" to images.size.toString(), "dbBytes" to dbFile.length().toString()))

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
                AppDatabase.getInstance(context)
            }
            val result = ExportResult(images.size, copied, missing)
            if (missing.isNotEmpty()) run.warning("Backup completed with missing image payloads", mapOf("missing" to missing.size.toString()))
            run.success("PMAI backup created", mapOf("images" to images.size.toString(), "copied" to copied.toString(), "missing" to missing.size.toString()))
            result
        } catch (t: Throwable) {
            run.failure("EXPORT", t)
            throw t
        }
    }

    suspend fun importBackup(source: Uri, progress: (Int) -> Unit = {}): ImportResult = withContext(Dispatchers.IO) {
        val run = diagnostics.begin("BACKUP_IMPORT", mapOf("source" to source.toString()))
        val tempRoot = File(context.cacheDir, "pmai_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            run.stage("READ_ARCHIVE", "Copying PMAI archive to temporary storage")
            val archive = File(tempRoot, "backup.pmai")
            context.contentResolver.openInputStream(source)?.use { input -> FileOutputStream(archive).use { input.copyTo(it) } } ?: error("تعذر فتح ملف النسخة الاحتياطية")
            ZipFile(archive).use { zip ->
                val dbEntry = zip.getEntry(DB_ENTRY) ?: error("النسخة لا تحتوي على personal_memory.db")
                require(zip.getEntry(MANIFEST_ENTRY) != null) { "ملف النسخة غير صالح" }
                run.stage("VALIDATE", "Validated PMAI manifest and database entry")
                val stagedDb = File(tempRoot, "personal_memory.db")
                zip.getInputStream(dbEntry).use { input -> FileOutputStream(stagedDb).use { input.copyTo(it) } }
                require(stagedDb.isFile && stagedDb.length() > 0) { "قاعدة البيانات داخل النسخة فارغة" }

                run.stage("RESTORE_DATABASE", "Replacing local database snapshot")
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
                run.stage("RESTORE_IMAGES", "Restoring managed image payloads", mapOf("entries" to imageEntries.size.toString()))
                imageEntries.forEachIndexed { index, entry ->
                    val name = entry.name.removePrefix(IMAGE_PREFIX)
                    val id = name.substringBefore('_').toLongOrNull() ?: return@forEachIndexed
                    val row = imageRows[id] ?: return@forEachIndexed
                    val target = File(libraryDir, "${id}_${safeName(row.fileName)}")
                    zip.getInputStream(entry).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                    require(target.isFile && target.length() > 0) { "فشل استعادة الصورة ${row.fileName}" }
                    db.openHelper.writableDatabase.execSQL("UPDATE images SET uri = ?, filePath = ? WHERE id = ?", arrayOf(Uri.fromFile(target).toString(), target.absolutePath, id))
                    restored++
                    progress(((index + 1) * 100 / imageEntries.size.coerceAtLeast(1)))
                }
                val result = ImportResult(imageRows.size, restored)
                run.success("PMAI restore completed", mapOf("records" to imageRows.size.toString(), "restoredImages" to restored.toString()))
                result
            }
        } catch (t: Throwable) {
            run.failure("IMPORT", t)
            throw t
        } finally { tempRoot.deleteRecursively() }
    }

    private fun openUri(value: String): InputStream? = try {
        val uri = Uri.parse(value)
        if (uri.scheme.equals("file", true)) uri.path?.let(::File)?.inputStream() else context.contentResolver.openInputStream(uri)
    } catch (_: Exception) { null }

    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "image" }
    data class ExportResult(val imageCount: Int, val copiedImages: Int, val missingImageIds: List<Long>)
    data class ImportResult(val imageCount: Int, val restoredImages: Int)
}
