package com.example.personalmemoryai.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.personalmemoryai.R
import com.example.personalmemoryai.advancedvisual.AdvancedVisualIntelligenceService
import com.example.personalmemoryai.databinding.ItemAdvancedVisualResultBinding

class AdvancedVisualResultAdapter(
    private val onImageClick: (String?) -> Unit
) : RecyclerView.Adapter<AdvancedVisualResultAdapter.ViewHolder>() {
    private var items: List<AdvancedVisualIntelligenceService.Evidence> = emptyList()

    fun submitList(next: List<AdvancedVisualIntelligenceService.Evidence>) {
        items = next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemAdvancedVisualResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemAdvancedVisualResultBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AdvancedVisualIntelligenceService.Evidence) {
            binding.root.setOnClickListener { onImageClick(item.filePath) }
            binding.nameText.text = item.displayName
            binding.scoreText.text = "${item.finalPercent}% FINAL"
            binding.explainText.text = "" +
                "Existing classical: ${item.baseClassicalPercent}%  |  Advanced: ${item.advancedPercent}%\n" +
                "Structure ${item.structurePercent}%  •  Color ${item.advancedColorPercent}%  •  Texture ${item.texturePercent}%\n" +
                "Gradient ${item.gradientPercent}%  •  Layout ${item.layoutPercent}%  •  RANSAC ${item.ransacInliers}\n" +
                "Reasons: ${item.evidenceReasons.joinToString(", ").ifBlank { "insufficient evidence" }}\n" +
                "Components: Haar ${item.haarPercent}% • pHash ${item.phashPercent}% • dHash ${item.dhashPercent}% • Edge ${item.edgePercent}% • Local ${item.localPercent}%"
            binding.root.contentDescription = "${item.displayName}, ${item.finalPercent} percent similarity"
        }
    }
}
