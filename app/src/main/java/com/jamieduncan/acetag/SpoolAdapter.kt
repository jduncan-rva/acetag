package com.jamieduncan.acetag

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jamieduncan.acetag.data.SpoolGroup

/**
 * One line per kind of filament, with a count when you have more than one. The underlying spools
 * stay separate rows — see [SpoolGroup].
 */
class SpoolAdapter(
    private val onClick: (SpoolGroup) -> Unit,
) : RecyclerView.Adapter<SpoolAdapter.VH>() {

    private var items: List<SpoolGroup> = emptyList()

    fun submitList(list: List<SpoolGroup>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_spool, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val swatch: View = view.findViewById(R.id.itemSwatch)
        private val title: TextView = view.findViewById(R.id.itemTitle)
        private val subtitle: TextView = view.findViewById(R.id.itemSubtitle)
        private val abrasive: TextView = view.findViewById(R.id.itemAbrasive)
        private val untagged: TextView = view.findViewById(R.id.itemUntagged)
        private val warning: TextView = view.findViewById(R.id.itemWarning)
        private val count: TextView = view.findViewById(R.id.itemCount)

        fun bind(group: SpoolGroup) {
            val spec = group.spec()
            // Titled from the spool, not the spec: a wood-filled PLA writes plain "PLA" to its tag.
            title.text = SpoolDisplay.title(group.newest)
            subtitle.text = SpoolDisplay.summary(spec)
            abrasive.text = SpoolDisplay.ABRASIVE_LABEL
            abrasive.visibility = if (group.isAbrasive) View.VISIBLE else View.GONE
            // Plain text, no warning styling: filament waiting for tags is filament you own.
            val tagState = SpoolDisplay.tagState(group.taggedCount, group.count)
            untagged.text = tagState.orEmpty()
            untagged.visibility = if (tagState == null) View.GONE else View.VISIBLE
            warning.visibility = if (group.hasStaleTags) View.VISIBLE else View.GONE
            count.text = "×${group.count}"
            count.visibility = if (group.count > 1) View.VISIBLE else View.GONE
            swatch.setSwatchColor(group.color)
            itemView.setOnClickListener { onClick(group) }
        }
    }
}
