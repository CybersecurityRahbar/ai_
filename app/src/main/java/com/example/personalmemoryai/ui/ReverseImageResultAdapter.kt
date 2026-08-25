package com.example.personalmemoryai.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.personalmemoryai.databinding.ItemReverseImageResultBinding
import com.example.personalmemoryai.reverseimage.ReverseImageSearchService
import java.io.File

class ReverseImageResultAdapter(
    private val onClick: (ReverseImageSearchService.Result) -> Unit
) : RecyclerView.Adapter<ReverseImageResultAdapter.ViewHolder>() {
    private val items = mutableListOf<ReverseImageSearchService.Result>()

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

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
            val localPath = result.item.filePath?.takeIf { File(it).isFile }
            binding.resultImage.setImageURI(
                localPath?.let { Uri.fromFile(File(it)) } ?: Uri.parse(result.item.uri)
            )
            binding.fileNameText.text = result.item.displayName
            binding.pathText.text = localPath ?: result.item.filePath ?: result.item.uri
            binding.scoreText.text = "${result.percent}%  • Haar ${result.matchedCoefficients}  • AKAZE ${result.localPercent}% / RANSAC ${result.ransacInliers}"
            binding.detailText.text = "pHash ${result.phashPercent}%  • dHash ${result.dhashPercent}%  • Color ${result.colorPercent}%  • Shape ${result.edgePercent}%  • Local ${result.localMatches} matches"
            binding.root.setOnClickListener { onClick(result) }
        }
    }
}
