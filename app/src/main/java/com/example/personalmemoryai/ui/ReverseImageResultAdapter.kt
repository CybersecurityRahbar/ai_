package com.example.personalmemoryai.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.personalmemoryai.databinding.ItemReverseImageResultBinding
import com.example.personalmemoryai.reverseimage.ReverseImageSearchService

class ReverseImageResultAdapter(
    private val onClick: (ReverseImageSearchService.Result) -> Unit
) : RecyclerView.Adapter<ReverseImageResultAdapter.ViewHolder>() {

    private val items = mutableListOf<ReverseImageSearchService.Result>()

    fun submitList(results: List<ReverseImageSearchService.Result>) {
        items.clear()
        items.addAll(results)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        ItemReverseImageResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemReverseImageResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(result: ReverseImageSearchService.Result) {
            binding.resultImage.setImageURI(android.net.Uri.parse(result.image.uri))
            binding.fileNameText.text = result.image.fileName
            binding.pathText.text = result.image.filePath ?: result.image.uri
            binding.scoreText.text = "${result.percent}% • ${result.matchedCoefficients} matched coefficients"
            binding.root.setOnClickListener { onClick(result) }
        }
    }
}
