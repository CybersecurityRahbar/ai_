package com.example.personalmemoryai.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
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
import java.util.UUID

class BulkImagePickerActivity : AppCompatActivity() {
    companion object {
        const val RESULT_QUEUE_FILE = "result_queue_file"
        private const val PAGE_SIZE = 100
        private const val REQUEST_MEDIA_PERMISSION = 7301
        private const val EXTRA_TITLE = "title"

        fun launchIntent(title: String = "SELECT LOCAL IMAGES"): Intent = Intent().apply { putExtra(EXTRA_TITLE, title) }
    }

    private val rows = mutableListOf<ImageRow>()
    private val selected = linkedSetOf<String>()
    private val volumes = mutableListOf<String>()
    private var offset = 0
    private var loading = false
    private var allSelected = false
    private var volumeIndex = 0
    private lateinit var adapter: ImagePickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_image_picker)
        findViewById<TextView>(R.id.titleText).text = intent.getStringExtra(EXTRA_TITLE) ?: "SELECT LOCAL IMAGES"
        adapter = ImagePickerAdapter { row, checked ->
            if (checked) selected.add(row.uri.toString()) else selected.remove(row.uri.toString())
            updateSummary()
        }
        findViewById<RecyclerView>(R.id.imagesRecyclerView).apply {
            layoutManager = GridLayoutManager(this@BulkImagePickerActivity, 3)
            adapter = this@BulkImagePickerActivity.adapter
        }
        findViewById<View>(R.id.loadMoreButton).setOnClickListener { loadPage() }
        findViewById<View>(R.id.selectAllButton).setOnClickListener { selectAllMedia() }
        findViewById<View>(R.id.doneButton).setOnClickListener { finishWithSelection() }
        if (hasMediaPermission()) prepareVolumes() else requestMediaPermission()
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
        if (requestCode == REQUEST_MEDIA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            prepareVolumes()
        } else {
            Toast.makeText(this, "يلزم السماح بقراءة الصور لاستخدام المنتقي المحلي الكبير.", Toast.LENGTH_LONG).show()
        }
    }

    private fun prepareVolumes() {
        volumes.clear()
        volumes += MediaStore.VOLUME_EXTERNAL
        if (Build.VERSION.SDK_INT >= 30) volumes += MediaStore.getExternalVolumeNames(this).filterNot { it == MediaStore.VOLUME_EXTERNAL }
        volumeIndex = 0
        offset = 0
        rows.clear()
        adapter.submit(rows)
        loadPage()
    }

    private fun loadPage() {
        if (loading || volumeIndex >= volumes.size) return
        loading = true
        lifecycleScope.launch {
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
        }
    }

    private fun queryPage(volume: String, start: Int, limit: Int): List<ImageRow> {
        val collection = MediaStore.Images.Media.getContentUri(volume)
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)
        val out = ArrayList<ImageRow>(limit)
        if (Build.VERSION.SDK_INT >= 26) {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_LIMIT, limit)
                putInt(MediaStore.QUERY_ARG_OFFSET, start)
                putStringArray(MediaStore.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
                putInt(MediaStore.QUERY_ARG_SORT_DIRECTION, MediaStore.QUERY_SORT_DIRECTION_DESCENDING)
            }
            contentResolver.query(collection, projection, args, null)?.use { cursor -> readRows(cursor, collection, out) }
        } else {
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $limit OFFSET $start"
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
            if (allSelected) {
                selected.clear()
                allSelected = false
                rows.forEach { it.checked = false }
                adapter.submit(rows)
                updateSummary()
                return@launch
            }
            val allUris = withContext(Dispatchers.IO) { queryAllUris() }
            selected.clear()
            selected.addAll(allUris)
            allSelected = true
            rows.forEach { it.checked = true }
            adapter.submit(rows)
            updateSummary()
        }
    }

    private fun queryAllUris(): List<String> {
        val out = ArrayList<String>()
        for (volume in volumes) {
            val collection = MediaStore.Images.Media.getContentUri(volume)
            val projection = arrayOf(MediaStore.Images.Media._ID)
            contentResolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) out += Uri.withAppendedPath(collection, cursor.getLong(idIndex).toString()).toString()
            }
        }
        return out
    }

    private suspend fun writeSelectionFile(): File {
        val dir = File(filesDir, "reverse_image/selection_queue").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.uris")
        withContext(Dispatchers.IO) {
            file.bufferedWriter(Charsets.UTF_8).use { writer -> selected.forEach { writer.appendLine(it) } }
        }
        return file
    }

    private fun finishWithSelection() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "اختر صورة واحدة على الأقل.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val file = writeSelectionFile()
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_QUEUE_FILE, file.absolutePath))
            finish()
        }
    }

    private fun updateSummary() {
        findViewById<TextView>(R.id.summaryText).text = "VISIBLE ${rows.size} • SELECTED ${selected.size} • ${if (allSelected) "ALL MEDIA SELECTED" else "PAGED LOCAL GALLERY"}"
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
            private var current: ImageRow? = null
            init { check.setOnCheckedChangeListener { _, checked -> current?.let { listener(it, checked) } } }
            fun bind(row: ImageRow) {
                current = null
                name.text = row.name
                check.isChecked = row.checked
                current = row
            }
        }
    }
}
