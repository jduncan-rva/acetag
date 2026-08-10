package com.jamieduncan.acetag

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.jamieduncan.acetag.data.SpoolEntity
import com.jamieduncan.acetag.databinding.ItemSpoolBinding

class SpoolAdapter(private val onClick: (SpoolEntity) -> Unit) :
    RecyclerView.Adapter<SpoolAdapter.ViewHolder>() {

    private var items: List<SpoolEntity> = emptyList()

    fun submitList(newItems: List<SpoolEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSpoolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemSpoolBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(spool: SpoolEntity) {
            binding.itemTitle.text = "${spool.manufacturer} ${spool.type}"
            binding.itemSubtitle.text =
                "${spool.nozzleMin}-${spool.nozzleMax}°C nozzle • ${spool.weightG}g • ${spool.diameterMm}mm"
            binding.itemTagStatus.text = when {
                spool.tagUidA != null && spool.tagUidB != null -> "2 tags"
                spool.tagUidA != null -> "1 tag"
                else -> "no tags"
            }
            try {
                val bg = (binding.itemSwatch.background as GradientDrawable).mutate() as GradientDrawable
                bg.setColor(Color.parseColor(spool.color))
                binding.itemSwatch.background = bg
            } catch (_: Exception) {
                // leave default swatch background if color is unparseable
            }
            binding.root.setOnClickListener { onClick(spool) }
        }
    }
}
