package com.example.personalmemoryai.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.personalmemoryai.database.ImageEntity
import com.example.personalmemoryai.databinding.ItemImageResultBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImageResultAdapter(
    private val onImageClick: (ImageEntity) -> Unit
) : RecyclerView.Adapter<ImageResultAdapter.ViewHolder>() {

    private val items = mutableListOf<ImageEntity>()

    fun submitList(newItems: List<ImageEntity>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class ViewHolder(
        private val binding: ItemImageResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ImageEntity) {
            binding.fileName.text = item.fileName
            binding.filePath.text = item.filePath ?: item.uri
            binding.ocrText.text = item.ocrText.ifBlank { "لا يوجد نص مستخرج" }
            binding.language.text = item.ocrLanguage
            binding.detectedObjects.text = if (item.detectedObjects.isBlank()) {
                "الكائنات: لم يتم اكتشاف كائنات"
            } else {
                "الكائنات: ${item.detectedObjects}"
            }
            binding.date.text = formatDate(item.dateTaken ?: item.dateModified ?: item.indexedAt)
            binding.imagePreview.setImageURI(Uri.parse(item.uri))
            binding.root.setOnClickListener { onImageClick(item) }
            binding.imagePreview.setOnClickListener { onImageClick(item) }
        }

        private fun formatDate(time: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImageResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}
