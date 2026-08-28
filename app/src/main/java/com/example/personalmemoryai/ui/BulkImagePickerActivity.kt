package com.example.personalmemoryai.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.UUID

/**
 * Large local-image browser.
 *
 * Important design rule: the integrated MediaStore browser never mixes the
 * aggregate VOLUME_EXTERNAL view with concrete external volumes because that
 * would count the same media more than once.
 */
class BulkImagePickerActivity : AppCompatActivity() {
    companion object {
        const val RESULT_QUEUE_FILE = "result_queue_file"
        private const val PAGE_SIZE = 120
        private const val REQUEST_MEDIA_PERMISSION = 7301
        private const val EXTRA_TITLE = "title"
        private const val MAX_DIRECT_SYSTEM_SELECTION = 1500
        private const val THUMB_SIZE = 220

        fun launchIntent(title: String = "SELECT LOCAL IMAGES"): Intent = Intent(thisClassPlaceholder()).apply {
            putExtra(EXTRA_TITLE, title)
        }

        // Kept as a helper only for call sites that create the explicit class intent below.
        private fun thisClassPlaceholder(): Class<BulkImagePickerActivity> = BulkImagePickerActivity::class.java
    }

    private val rows = mutableListOf<ImageRow>()
    private val selected = linkedSetOf<String>()
    private val volumes = mutableListOf<String>()
    private var selectedAllCount = 0
    private var offset = 0
    private var loading = false
    private var allSelected = false
    private var volumeIndex = 0
    private lateinit var adapter: ImagePickerAdapter

    private val systemFilesPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        handleSystemUriSelection(uris)
    }

    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val data = result.data!!
        val uris = ArrayList<Uri>()
        data.data?.let(uris::add)
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
        }
        if (uris.isNotEmpty()) handleSystemUriSelection(uris)
        else Toast.makeText(this, "لم تُختر أي صورة.", Toast.LENGTH_SHORT).show()
    }

    private val galleryFallbackPicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) handleSystemUriSelection(uris)
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Throwable) {
                    // Some providers grant tree access without persistable flags.
                }
                val queue = withContext(Dispatchers.IO) { writeImageTreeQueue(treeUri) }
                val count = withContext(Dispatchers.IO) { countQueueLines(queue) }
                if (count == 0) {
                    queue.delete()
                    Toast.makeText(this@BulkImagePickerActivity, "لا توجد صور مدعومة داخل المجلد المحدد.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_QUEUE_FILE, queue.absolutePath))
                Toast.makeText(this@BulkImagePickerActivity, "تم تجهيز $count صورة من المجلد دون تحميل آلاف العناصر إلى الذاكرة.", Toast.LENGTH_LONG).show()
                finish()
            } catch (t: Throwable) {
                Toast.makeText(this@BulkImagePickerActivity, "تعذر قراءة المجلد: ${t.message ?: "خطأ غير محدد"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_image_picker)
        findViewById<TextView>(R.id.titleText).text = intent.getStringExtra(EXTRA_TITLE) ?: "SELECT LOCAL IMAGES"
        adapter = ImagePickerAdapter { row, checked ->
            if (checked) selected.add(row.uri.toString()) else selected.remove(row.uri.toString())
            if (allSelected) allSelected = false
            selectedAllCount = 0
            updateSummary()
        }
        findViewById<RecyclerView>(R.id.imagesRecyclerView).apply {
            layoutManager = GridLayoutManager(this@BulkImagePickerActivity, 3)
            adapter = this@BulkImagePickerActivity.adapter
        }
        findViewById<View>(R.id.openSystemImagesButton).setOnClickListener { showSourceChooser() }
        findViewById<View>(R.id.loadMoreButton).setOnClickListener { loadPage() }
        findViewById<View>(R.id.selectAllButton).setOnClickListener { selectAllMedia() }
        findViewById<View>(R.id.doneButton).setOnClickListener { finishWithSelection() }
        if (hasMediaPermission()) prepareVolumes() else requestMediaPermission()
    }

    private fun showSourceChooser() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_visual_source_chooser, null)
        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialogView.findViewById<Button>(R.id.gallerySourceButton).setOnClickListener {
            dialog.dismiss()
            launchGallery()
        }
        dialogView.findViewById<Button>(R.id.filesSourceButton).setOnClickListener {
            dialog.dismiss()
            systemFilesPicker.launch(arrayOf("image/*"))
        }
        dialogView.findViewById<Button>(R.id.folderSourceButton).setOnClickListener {
            dialog.dismiss()
            folderPicker.launch(null)
        }
        dialog.show()
    }

    private fun launchGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            if (intent.resolveActivity(packageManager) != null) galleryPicker.launch(intent)
            else galleryFallbackPicker.launch("image/*")
        } catch (_: Throwable) {
            galleryFallbackPicker.launch("image/*")
        }
    }

    private fun handleSystemUriSelection(uris: Collection<Uri>) {
        val distinctUris = uris.distinct()
        lifecycleScope.launch {
            if (distinctUris.size > MAX_DIRECT_SYSTEM_SELECTION) {
                Toast.makeText(
                    this@BulkImagePickerActivity,
                    "تم اختيار ${distinctUris.size} عنصرًا. للاستيراد الضخم استخدم خيار اختيار المجلد؛ فهو يتجنب نقل آلاف URIs عبر Binder دفعة واحدة.",
                    Toast.LENGTH_LONG
                ).show()
            }
            var persisted = 0
            distinctUris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    persisted++
                } catch (_: Throwable) {
                    // The provider may expose only temporary access; the queue is consumed immediately.
                }
            }
            try {
                val file = writeUriSelectionFile(distinctUris)
                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_QUEUE_FILE, file.absolutePath))
                Toast.makeText(
                    this@BulkImagePickerActivity,
                    "تم تجهيز ${distinctUris.size} صورة من المصدر • صلاحيات مستمرة: $persisted",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } catch (t: Throwable) {
                Toast.makeText(this@BulkImagePickerActivity, "تعذر تجهيز قائمة الصور: ${t.message ?: "خطأ غير محدد"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hasMediaPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMediaPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_MEDIA_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) prepareVolumes()
        else Toast.makeText(this, "يلزم السماح بقراءة الصور لاستخدام متصفح الصور المحلي. ويمكنك مع ذلك استخدام Files أو Folder.", Toast.LENGTH_LONG).show()
    }

    private fun prepareVolumes() {
        volumes.clear()
        if (Build.VERSION.SDK_INT >= 30) {
            // IMPORTANT: do not add MediaStore.VOLUME_EXTERNAL here; it is an aggregate view.
            volumes += MediaStore.getExternalVolumeNames(this).sorted()
            if (volumes.isEmpty()) volumes += MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            volumes += MediaStore.VOLUME_EXTERNAL
        }
        volumeIndex = 0
        offset = 0
        allSelected = false
        selectedAllCount = 0
        rows.clear()
        selected.clear()
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
                rows += result
                offset += result.size
                adapter.submit(rows)
                loading = false
                updateSummary()
            } catch (t: Throwable) {
                loading = false
                Toast.makeText(this@BulkImagePickerActivity, "تعذر قراءة صفحة الصور: ${t.message ?: "خطأ غير محدد"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun queryPage(volume: String, start: Int, limit: Int): List<ImageRow> {
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val out = ArrayList<ImageRow>(limit)
        if (Build.VERSION.SDK_INT >= 26) {
            val args = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, start)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_MODIFIED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            contentResolver.query(collection, projection, args, null)?.use { cursor -> readRows(cursor, collection, out) }
        } else {
            val sort = "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT $limit OFFSET $start"
            contentResolver.query(collection, projection, null, null, sort)?.use { cursor -> readRows(cursor, collection, out) }
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
            out += ImageRow(
                uri = Uri.withAppendedPath(collection, id.toString()),
                name = cursor.getString(nameIndex) ?: "image_$id",
                size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L,
                width = if (widthIndex >= 0 && !cursor.isNull(widthIndex)) cursor.getInt(widthIndex) else 0,
                height = if (heightIndex >= 0 && !cursor.isNull(heightIndex)) cursor.getInt(heightIndex) else 0
            )
        }
    }

    private fun selectAllMedia() {
        lifecycleScope.launch {
            try {
                if (allSelected) {
                    allSelected = false
                    selectedAllCount = 0
                    selected.clear()
                    rows.forEach { it.checked = false }
                    adapter.submit(rows)
                    updateSummary()
                    return@launch
                }
                val count = withContext(Dispatchers.IO) { queryAllMediaCount() }
                if (count <= 0) {
                    Toast.makeText(this@BulkImagePickerActivity, "لا توجد صور للانتقاء.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                allSelected = true
                selectedAllCount = count
                selected.clear()
                rows.forEach { it.checked = true }
                adapter.submit(rows)
                updateSummary()
            } catch (t: Throwable) {
                Toast.makeText(this@BulkImagePickerActivity, "تعذر تحديد جميع الصور: ${t.message ?: "خطأ غير محدد"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun queryAllMediaCount(): Int {
        var count = 0
        for (volume in volumes) {
            val collection = MediaStore.Images.Media.getContentUri(volume)
            contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
                count += cursor.count
            }
        }
        return count
    }

    private suspend fun writeAllMediaSelectionFile(): File = withContext(Dispatchers.IO) {
        val dir = File(filesDir, "reverse_image/selection_queue").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.uris")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (volume in volumes) {
                val collection = MediaStore.Images.Media.getContentUri(volume)
                contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) writer.appendLine(Uri.withAppendedPath(collection, cursor.getLong(idIndex)).toString())
                }
            }
        }
        file
    }

    private suspend fun writeImageTreeQueue(treeUri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(filesDir, "reverse_image/selection_queue").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.uris")
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val roots = ArrayDeque<String>()
            roots.add(rootId)
            while (roots.isNotEmpty()) {
                val parentId = roots.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                contentResolver.query(children, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null)?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIndex) ?: continue
                        val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) ?: "" else ""
                        val mime = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) cursor.getString(mimeIndex) ?: "" else ""
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                            roots.addLast(id)
                        } else if (isLikelyImage(name, mime)) {
                            writer.appendLine(childUri.toString())
                        }
                    }
                }
            }
        }
        file
    }

    private fun isLikelyImage(name: String, mime: String): Boolean {
        if (mime.startsWith("image/", ignoreCase = true)) return true
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif") ||
            lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".avif") ||
            lower.endsWith(".tif") || lower.endsWith(".tiff")
    }

    private suspend fun countQueueLines(file: File): Int = withContext(Dispatchers.IO) {
        file.useLines(Charsets.UTF_8) { it.count { line -> line.isNotBlank() } }
    }

    private suspend fun writeUriSelectionFile(uris: Collection<Uri>): File {
        val dir = File(filesDir, "reverse_image/selection_queue").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.uris")
        withContext(Dispatchers.IO) {
            file.bufferedWriter(Charsets.UTF_8).use { writer -> uris.forEach { writer.appendLine(it.toString()) } }
        }
        return file
    }

    private suspend fun writeSelectionFile(): File {
        return if (allSelected) writeAllMediaSelectionFile() else writeUriSelectionFile(selected.map(Uri::parse))
    }

    private fun finishWithSelection() {
        if (!allSelected && selected.isEmpty()) {
            Toast.makeText(this, "اختر صورة واحدة على الأقل.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val file = writeSelectionFile()
                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_QUEUE_FILE, file.absolutePath))
                finish()
            } catch (t: Throwable) {
                Toast.makeText(this@BulkImagePickerActivity, "تعذر حفظ الاختيارات: ${t.message ?: "خطأ غير محدد"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateSummary() {
        val selectedCount = if (allSelected) selectedAllCount else selected.size
        val state = if (allSelected) "ALL MEDIA SELECTED" else "PAGED LOCAL GALLERY"
        findViewById<TextView>(R.id.summaryText).text = "VISIBLE ${rows.size} • SELECTED $selectedCount • $state"
    }

    data class ImageRow(val uri: Uri, val name: String, val size: Long, val width: Int, val height: Int, var checked: Boolean = false)

    private class ImagePickerAdapter(private val onChecked: (ImageRow, Boolean) -> Unit) : RecyclerView.Adapter<ImagePickerAdapter.Holder>() {
        private var items: List<ImageRow> = emptyList()
        fun submit(next: List<ImageRow>) { items = next.toList(); notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_bulk_image_picker, parent, false), onChecked)
        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size
        class Holder(view: View, private val listener: (ImageRow, Boolean) -> Unit) : RecyclerView.ViewHolder(view) {
            private val check: CheckBox = view.findViewById(R.id.checkBox)
            private val name: TextView = view.findViewById(R.id.nameText)
            private val thumb: ImageView = view.findViewById(R.id.thumbImage)
            private var current: ImageRow? = null
            init { check.setOnCheckedChangeListener { _, checked -> current?.let { listener(it, checked) } } }
            fun bind(row: ImageRow) {
                current = null
                name.text = row.name
                check.isChecked = row.checked
                thumb.setImageDrawable(null)
                val resolver = itemView.context.contentResolver
                itemView.post {
                    val expected = row.uri.toString()
                    runCatching {
                        val bitmap: Bitmap = resolver.loadThumbnail(row.uri, Size(THUMB_SIZE, THUMB_SIZE), null)
                        if (current?.uri?.toString() == expected) thumb.setImageBitmap(bitmap) else if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
                current = row
            }
        }
    }
}
