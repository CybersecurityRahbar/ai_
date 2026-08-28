package com.example.personalmemoryai.ui

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.personalmemoryai.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * Large-scale image selector. It keeps native Gallery, native System Files,
 * folder/tree import, and the in-app MediaStore browser as separate acquisition paths.
 */
class BulkImagePickerActivity : AppCompatActivity() {
    companion object {
        const val RESULT_QUEUE_FILE = "result_queue_file"
        private const val PAGE_SIZE = 200
        private const val REQUEST_MEDIA_PERMISSION = 7301
        private const val EXTRA_TITLE = "title"
        private const val SOURCE_GALLERY = 0
        private const val SOURCE_FILES = 1
        private const val SOURCE_FOLDER = 2
        private const val SOURCE_IN_APP = 3

        fun launchIntent(title: String = "SELECT LOCAL IMAGES"): Intent = Intent().apply {
            setClassName(
                "com.example.personalmemoryai",
                "com.example.personalmemoryai.ui.BulkImagePickerActivity"
            )
            putExtra(EXTRA_TITLE, title)
        }
    }

    private enum class SortMode(val label: String, val columns: Array<String>, val descending: Boolean) {
        MODIFIED_NEWEST("DATE MODIFIED ↓", arrayOf(MediaStore.Images.Media.DATE_MODIFIED), true),
        ADDED_NEWEST("DATE ADDED ↓", arrayOf(MediaStore.Images.Media.DATE_ADDED), true),
        NAME_AZ("NAME A–Z", arrayOf(MediaStore.Images.Media.DISPLAY_NAME), false),
        NAME_ZA("NAME Z–A", arrayOf(MediaStore.Images.Media.DISPLAY_NAME), true)
    }

    private val rows = mutableListOf<ImageRow>()
    private val selected = linkedSetOf<String>()
    private val excludedFromAll = linkedSetOf<String>()
    private val volumes = mutableListOf<String>()
    private var offset = 0
    private var loading = false
    private var allSelected = false
    private var volumeIndex = 0
    private var allMediaCount = 0L
    private var sortMode = SortMode.MODIFIED_NEWEST
    private lateinit var adapter: ImagePickerAdapter

    private val systemImagesPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            setBusy(true)
            try {
                val distinct = uris.distinct()
                val file = writePreparedUriQueue(distinct)
                completeWithQueue(file, "تم تجهيز ${distinct.size} صورة من ملفات النظام.")
            } catch (t: Throwable) {
                showError("تعذر تجهيز الصور من ملفات النظام: ${t.message ?: "خطأ غير محدد"}")
            } finally { setBusy(false) }
        }
    }

    private val galleryPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uris = extractUris(result.data)
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            setBusy(true)
            try {
                val distinct = uris.distinct()
                val file = writePreparedUriQueue(distinct)
                completeWithQueue(file, "تم تجهيز ${distinct.size} صورة من الاستوديو.")
            } catch (t: Throwable) {
                showError("تعذر تجهيز صور الاستوديو: ${t.message ?: "خطأ غير محدد"}")
            } finally { setBusy(false) }
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@registerForActivityResult
        lifecycleScope.launch {
            setBusy(true)
            try {
                try { contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
                val file = withContext(Dispatchers.IO) { writeTreeQueue(treeUri) }
                completeWithQueue(file, "تم تجهيز صور المجلد بطريقة تيارية مناسبة للآلاف.")
            } catch (t: Throwable) {
                showError("تعذر قراءة المجلد: ${t.message ?: "خطأ غير محدد"}")
            } finally { setBusy(false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_image_picker)
        findViewById<TextView>(R.id.titleText).text = intent.getStringExtra(EXTRA_TITLE) ?: "SELECT LOCAL IMAGES"
        adapter = ImagePickerAdapter { row, checked ->
            val key = row.uri.toString()
            if (allSelected) {
                if (checked) excludedFromAll.remove(key) else excludedFromAll.add(key)
            } else {
                if (checked) selected.add(key) else selected.remove(key)
            }
            row.checked = checked
            updateSummary()
        }
        findViewById<RecyclerView>(R.id.imagesRecyclerView).apply {
            layoutManager = GridLayoutManager(this@BulkImagePickerActivity, 3)
            adapter = this@BulkImagePickerActivity.adapter
        }
        findViewById<View>(R.id.openSystemImagesButton).setOnClickListener { showSourceChooser() }
        findViewById<View>(R.id.sortButton).setOnClickListener { cycleSort() }
        findViewById<View>(R.id.loadMoreButton).setOnClickListener { loadPage() }
        findViewById<View>(R.id.selectAllButton).setOnClickListener { selectAllMedia() }
        findViewById<View>(R.id.doneButton).setOnClickListener { finishWithSelection() }
        if (hasMediaPermission()) prepareVolumes() else requestMediaPermission()
    }

    private fun showSourceChooser() {
        AlertDialog.Builder(this)
            .setTitle("مصدر الصور")
            .setItems(arrayOf(
                "الاستوديو / Gallery — اختيار صور",
                "ملفات النظام — اختيار صور",
                "اختيار مجلد كامل — الأفضل لآلاف الصور",
                "المنتقي المدمج — تصفح كل الصور داخل التطبيق"
            )) { _, which ->
                when (which) {
                    SOURCE_GALLERY -> openNativeGallery()
                    SOURCE_FILES -> systemImagesPicker.launch(arrayOf("image/*"))
                    SOURCE_FOLDER -> folderPicker.launch(null)
                    SOURCE_IN_APP -> prepareVolumes()
                }
            }
            .show()
    }

    private fun openNativeGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            data = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        if (packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()) galleryPicker.launch(intent)
        else {
            Toast.makeText(this, "لا يوجد تطبيق Gallery متعدد الاختيار؛ تم فتح المنتقي المدمج.", Toast.LENGTH_LONG).show()
            prepareVolumes()
        }
    }

    private fun hasMediaPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMediaPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_MEDIA_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) prepareVolumes()
        else Toast.makeText(this, "يمكنك استخدام Gallery أو Files أو اختيار مجلد حتى بدون المنتقي المدمج.", Toast.LENGTH_LONG).show()
    }

    private fun prepareVolumes() {
        volumes.clear()
        if (Build.VERSION.SDK_INT >= 29) volumes += MediaStore.getExternalVolumeNames(this).toList() else volumes += MediaStore.VOLUME_EXTERNAL
        volumeIndex = 0
        offset = 0
        allSelected = false
        allMediaCount = 0L
        rows.clear()
        selected.clear()
        excludedFromAll.clear()
        adapter.submit(rows)
        findViewById<TextView>(R.id.sortButtonLabel).text = sortMode.label
        lifecycleScope.launch {
            allMediaCount = withContext(Dispatchers.IO) { queryMediaCount() }
            updateSummary()
            loadPage()
        }
    }

    private fun cycleSort() {
        sortMode = when (sortMode) {
            SortMode.MODIFIED_NEWEST -> SortMode.ADDED_NEWEST
            SortMode.ADDED_NEWEST -> SortMode.NAME_AZ
            SortMode.NAME_AZ -> SortMode.NAME_ZA
            SortMode.NAME_ZA -> SortMode.MODIFIED_NEWEST
        }
        findViewById<TextView>(R.id.sortButtonLabel).text = sortMode.label
        offset = 0
        volumeIndex = 0
        rows.clear()
        adapter.submit(rows)
        loadPage()
    }

    private fun loadPage() {
        if (loading || volumeIndex >= volumes.size) return
        loading = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { queryPage(volumes[volumeIndex], offset, PAGE_SIZE) }
                if (result.isEmpty()) {
                    volumeIndex++
                    offset = 0
                    loading = false
                    if (volumeIndex < volumes.size) loadPage() else updateSummary()
                    return@launch
                }
                result.forEach { it.checked = isSelected(it.uri) }
                rows += result
                offset += result.size
                adapter.submit(rows)
                loading = false
                updateSummary()
            } catch (t: Throwable) {
                loading = false
                showError("تعذر قراءة صفحة الصور: ${t.message ?: "خطأ غير محدد"}")
            }
        }
    }

    private fun queryPage(volume: String, start: Int, limit: Int): List<ImageRow> {
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)
        val out = ArrayList<ImageRow>(limit)
        if (Build.VERSION.SDK_INT >= 26) {
            val args = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, start)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, sortMode.columns)
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, if (sortMode.descending) ContentResolver.QUERY_SORT_DIRECTION_DESCENDING else ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
            }
            contentResolver.query(collection, projection, args, null)?.use { cursor -> readRows(cursor, collection, out) }
        } else {
            val direction = if (sortMode.descending) "DESC" else "ASC"
            contentResolver.query(collection, projection, null, null, "${sortMode.columns.first()} $direction LIMIT $limit OFFSET $start")?.use { cursor -> readRows(cursor, collection, out) }
        }
        return out
    }

    private fun readRows(cursor: android.database.Cursor, collection: Uri, out: MutableList<ImageRow>) {
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
        val widthIndex = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
        val heightIndex = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIndex)
            out += ImageRow(Uri.withAppendedPath(collection, id.toString()), cursor.getString(nameIndex) ?: "image_$id",
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L,
                if (widthIndex >= 0 && !cursor.isNull(widthIndex)) cursor.getInt(widthIndex) else 0,
                if (heightIndex >= 0 && !cursor.isNull(heightIndex)) cursor.getInt(heightIndex) else 0)
        }
    }

    private fun queryMediaCount(): Long {
        var total = 0L
        for (volume in volumes) {
            val collection = MediaStore.Images.Media.getContentUri(volume)
            contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { total += it.count.toLong() }
        }
        return total
    }

    private fun selectAllMedia() {
        lifecycleScope.launch {
            allMediaCount = withContext(Dispatchers.IO) { queryMediaCount() }
            if (allSelected) {
                allSelected = false
                selected.clear()
                excludedFromAll.clear()
            } else {
                allSelected = true
                selected.clear()
                excludedFromAll.clear()
            }
            rows.forEach { it.checked = isSelected(it.uri) }
            adapter.submit(rows)
            updateSummary()
        }
    }

    private fun isSelected(uri: Uri): Boolean = if (allSelected) !excludedFromAll.contains(uri.toString()) else selected.contains(uri.toString())
    private fun selectedCount(): Long = if (allSelected) (allMediaCount - excludedFromAll.size).coerceAtLeast(0L) else selected.size.toLong()

    private suspend fun writePreparedUriQueue(uris: Collection<Uri>): File {
        val dir = File(filesDir, "reverse_image/selection_queue").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.uris")
        val dispatcher = Dispatchers.IO.limitedParallelism(4)
        val entries = coroutineScope { uris.map { uri -> async(dispatcher) { prepareQueueEntry(uri) } }.awaitAll() }
        withContext(Dispatchers.IO) { file.bufferedWriter(Charsets.UTF_8).use { writer -> entries.forEach { writer.appendLine(it) } } }
        return file
    }

    private fun prepareQueueEntry(uri: Uri): String {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); return uri.toString() }
        catch (_: SecurityException) { }
        catch (_: UnsupportedOperationException) { }
        val staged = stageUri(uri)
        return if (staged != null) Uri.fromFile(staged).toString() else uri.toString()
    }

    private fun stageUri(uri: Uri): File? = try {
        val dir = File(filesDir, "reverse_image/staging").apply { mkdirs() }
        val name = queryDisplayName(uri)?.replace(Regex("[^A-Za-z0-9._-]"), "_")?.take(100).orEmpty().ifBlank { "image" }
        val target = File(dir, "${UUID.randomUUID()}_$name")
        val input = openBestEffortStream(uri) ?: return null
        input.use { stream -> FileOutputStream(target).use { output -> stream.copyTo(output, 1024 * 1024) } }
        if (target.isFile && target.length() > 0L) target else null
    } catch (_: Throwable) { null }

    private fun openBestEffortStream(uri: Uri): java.io.InputStream? {
        if (uri.scheme == "file") return FileInputStream(File(uri.path ?: return null))
        return try { contentResolver.openInputStream(uri) } catch (_: Throwable) { null }
    }

    private suspend fun writeSelectionFile(): File = if (allSelected) writeAllMediaQueue() else writePreparedUriQueue(selected.map(Uri::parse))

    private suspend fun writeAllMediaQueue(): File {
        val dir = File(filesDir, "reverse_image/selection_queue").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.uris")
        withContext(Dispatchers.IO) {
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (volume in volumes) {
                    val collection = MediaStore.Images.Media.getContentUri(volume)
                    contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media._ID} ASC")?.use { cursor ->
                        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        while (cursor.moveToNext()) {
                            val uri = Uri.withAppendedPath(collection, cursor.getLong(idIndex).toString())
                            if (!excludedFromAll.contains(uri.toString())) writer.appendLine(uri.toString())
                        }
                    }
                }
            }
        }
        return file
    }

    private suspend fun writeTreeQueue(treeUri: Uri): File {
        val dir = File(filesDir, "reverse_image/selection_queue").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.uris")
        withContext(Dispatchers.IO) { file.bufferedWriter(Charsets.UTF_8).use { writer -> enumerateTree(treeUri) { writer.appendLine(it.toString()) } } }
        return file
    }

    private fun enumerateTree(treeUri: Uri, onImage: (Uri) -> Unit) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<String>().apply { add(rootId) }
        val visited = HashSet<String>()
        while (stack.isNotEmpty()) {
            val parentId = stack.removeLast()
            if (!visited.add(parentId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idIndex)
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    val name = cursor.getString(nameIndex).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) stack.add(childId)
                    else if (isImageMimeOrName(mime, name)) onImage(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId))
                }
            }
        }
    }

    private fun isImageMimeOrName(mime: String, name: String): Boolean {
        if (mime.startsWith("image/")) return true
        val lower = name.lowercase()
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic", ".heif", ".tif", ".tiff", ".avif").any(lower::endsWith)
    }

    private fun extractUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val out = ArrayList<Uri>()
        data.data?.let(out::add)
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add) }
        return out.distinct()
    }

    private fun completeWithQueue(file: File, message: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_QUEUE_FILE, file.absolutePath))
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun finishWithSelection() {
        lifecycleScope.launch {
            val count = selectedCount()
            if (count <= 0L) {
                Toast.makeText(this@BulkImagePickerActivity, "اختر صورة واحدة على الأقل.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            setBusy(true)
            try {
                val file = writeSelectionFile()
                completeWithQueue(file, "تم تجهيز $count صورة للاستيراد الخلفي.")
            } catch (t: Throwable) {
                showError("تعذر حفظ الاختيارات: ${t.message ?: "خطأ غير محدد"}")
            } finally { setBusy(false) }
        }
    }

    private fun updateSummary() {
        findViewById<TextView>(R.id.summaryText).text = "VISIBLE ${rows.size} • SELECTED ${selectedCount()} • TOTAL $allMediaCount • ${if (allSelected) "ALL MEDIA SELECTED" else "PAGED LOCAL GALLERY"}"
    }

    private fun setBusy(value: Boolean) {
        findViewById<View>(R.id.openSystemImagesButton).isEnabled = !value
        findViewById<View>(R.id.sortButton).isEnabled = !value
        findViewById<View>(R.id.selectAllButton).isEnabled = !value
        findViewById<View>(R.id.loadMoreButton).isEnabled = !value
        findViewById<View>(R.id.doneButton).isEnabled = !value
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        findViewById<TextView>(R.id.summaryText).text = message
    }

    data class ImageRow(val uri: Uri, val name: String, val size: Long, val width: Int, val height: Int, var checked: Boolean = false)

    private class ImagePickerAdapter(private val onChecked: (ImageRow, Boolean) -> Unit) : RecyclerView.Adapter<ImagePickerAdapter.Holder>() {
        private var items: List<ImageRow> = emptyList()
        fun submit(next: List<ImageRow>) { items = next.toList(); notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_bulk_image_picker, parent, false), onChecked)
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        class Holder(view: View, private val listener: (ImageRow, Boolean) -> Unit) : RecyclerView.ViewHolder(view) {
            private val image: ImageView = view.findViewById(R.id.thumbnailImage)
            private val check: CheckBox = view.findViewById(R.id.checkBox)
            private val name: TextView = view.findViewById(R.id.nameText)
            private var current: ImageRow? = null
            init { check.setOnCheckedChangeListener { _, checked -> current?.let { listener(it, checked) } } }
            fun bind(row: ImageRow) {
                current = null
                name.text = row.name
                check.isChecked = row.checked
                image.setImageDrawable(null)
                image.tag = row.uri.toString()
                val uri = row.uri
                image.post {
                    val view = image
                    val expected = uri.toString()
                    Thread {
                        val bitmap = try {
                            if (Build.VERSION.SDK_INT >= 29) view.context.contentResolver.loadThumbnail(uri, Size(320, 320), null)
                            else view.context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.Options().apply { inSampleSize = 4 }.let { options -> BitmapFactory.decodeStream(input, null, options) } }
                        } catch (_: Throwable) { null }
                        view.post { if (view.tag == expected && bitmap != null) view.setImageBitmap(bitmap) }
                    }.start()
                }
                current = row
            }
        }
    }
}
