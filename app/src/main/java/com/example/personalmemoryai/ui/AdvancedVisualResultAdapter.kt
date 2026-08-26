package com.example.personalmemoryai.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
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

    override fun onViewRecycled(holder: ViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(private val binding: ItemAdvancedVisualResultBinding) : RecyclerView.ViewHolder(binding.root) {
        private var expanded = false

        fun bind(item: AdvancedVisualIntelligenceService.Evidence) {
            expanded = false
            binding.explainText.visibility = View.GONE
            binding.whyToggleText.text = "WHY THIS RESULT  ▸"
            binding.root.setOnClickListener { onImageClick(item.filePath) }
            binding.resultImage.setImageDrawable(null)
            val path = item.filePath
            if (!path.isNullOrBlank()) {
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let { binding.resultImage.setImageBitmap(it) }
            }

            binding.nameText.text = item.displayName
            binding.scoreText.text = "${item.finalPercent}% FINAL"
            binding.detailText.text = "CONF ${item.confidencePercent}% • ADV ${item.advancedPercent}% • REG ${item.regionConsistencyPercent}% • STR ${item.structuralConsensusPercent}%"
            binding.pathText.text = item.bestQueryVariant

            binding.explainText.text = buildString {
                append("CONFIDENCE ${item.confidencePercent}%\n")
                append("WINNING QUERY VARIANT: ${item.bestQueryVariant}\n")
                append("CLASSICAL ${item.baseClassicalPercent}%  •  ADVANCED ${item.advancedPercent}%  •  REGIONAL ${item.regionConsistencyPercent}%\n")
                append("Structural consensus ${item.structuralConsensusPercent}% • coarse ${item.coarseStructurePercent}% • fine ${item.fineStructurePercent}%\n")
                append("Regional stability ${item.stableRegionPercent}% • disagreement ${item.spatialDisagreementPercent}%\n")
                append("Structure ${item.structurePercent}% • spatial color ${item.spatialColorPercent}%\n")
                append("Texture ${item.texturePercent}% • spatial texture ${item.spatialTexturePercent}%\n")
                append("Gradient ${item.gradientPercent}% • magnitude ${item.gradientMagnitudePercent}%\n")
                append("Layout ${item.layoutPercent}% • illumination ${item.illuminationPercent}%\n")
                append("Entropy ${item.entropyPercent}% • aspect ${item.aspectPercent}%\n")
                append("Existing: Haar ${item.haarPercent}% • pHash ${item.phashPercent}% • dHash ${item.dhashPercent}%\n")
                append("Existing color ${item.colorPercent}% • edge ${item.edgePercent}% • local ${item.localPercent}% • RANSAC ${item.ransacInliers}\n")
                append("WHY: ${item.evidenceReasons.joinToString(", ").ifBlank { "insufficient evidence" }}")
            }

            binding.whyToggleText.setOnClickListener {
                expanded = !expanded
                binding.explainText.visibility = if (expanded) View.VISIBLE else View.GONE
                binding.whyToggleText.text = if (expanded) "WHY THIS RESULT  ▾" else "WHY THIS RESULT  ▸"
            }
            binding.root.contentDescription = "${item.displayName}, ${item.finalPercent} percent similarity, confidence ${item.confidencePercent} percent, tap to open full image and details"
        }

        fun recycle() {
            binding.root.setOnClickListener(null)
            binding.whyToggleText.setOnClickListener(null)
            binding.resultImage.setImageDrawable(null)
            expanded = false
        }
    }
}
