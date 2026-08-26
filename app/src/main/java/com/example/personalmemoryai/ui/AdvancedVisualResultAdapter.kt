package com.example.personalmemoryai.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
            binding.scoreText.text = "${item.finalPercent}% FINAL • ADVANCED-V2"
            binding.explainText.text = buildString {
                append("CLASSICAL BASE ${item.baseClassicalPercent}%  |  ADVANCED ${item.advancedPercent}%\n")
                append("Advanced structure ${item.structurePercent}%  • spatial color ${item.spatialColorPercent}%\n")
                append("Texture ${item.texturePercent}%  • spatial texture ${item.spatialTexturePercent}%\n")
                append("Gradient ${item.gradientPercent}%  • magnitude ${item.gradientMagnitudePercent}%\n")
                append("Layout ${item.layoutPercent}%  • illumination ${item.illuminationPercent}%\n")
                append("Entropy ${item.entropyPercent}%  • aspect ${item.aspectPercent}%\n")
                append("Existing: Haar ${item.haarPercent}% • pHash ${item.phashPercent}% • dHash ${item.dhashPercent}%\n")
                append("Existing color ${item.colorPercent}% • edge ${item.edgePercent}% • local ${item.localPercent}% • RANSAC ${item.ransacInliers}\n")
                append("WHY: ${item.evidenceReasons.joinToString(", ").ifBlank { "insufficient evidence" }}")
            }
            binding.root.contentDescription = "${item.displayName}, ${item.finalPercent} percent similarity, explainable Advanced Visual Intelligence result"
        }
    }
}
