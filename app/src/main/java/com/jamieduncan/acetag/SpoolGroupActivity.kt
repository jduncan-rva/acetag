package com.jamieduncan.acetag

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jamieduncan.acetag.data.SpoolEntity
import com.jamieduncan.acetag.data.SpoolRepository
import com.jamieduncan.acetag.data.SpoolSource
import com.jamieduncan.acetag.databinding.ActivitySpoolGroupBinding
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Several spools of the same filament, shown together. Only reached when there's more than one —
 * a single spool goes straight to [SpoolDetailActivity], since a group of one is just a spool.
 *
 * "I've used one up" takes the oldest spool in the group. The spools are identical, so asking
 * which one you emptied would be a question with no answer; first in, first out is the only
 * defensible default. Anything spool-specific — editing, rewriting tags, removing a mistake —
 * still happens on the individual spool below.
 */
class SpoolGroupActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MANUFACTURER = "manufacturer"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_COLOR = "color"

        fun intent(context: Context, manufacturer: String, type: String, color: String): Intent =
            Intent(context, SpoolGroupActivity::class.java)
                .putExtra(EXTRA_MANUFACTURER, manufacturer)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_COLOR, color)
    }

    private lateinit var binding: ActivitySpoolGroupBinding
    private lateinit var adapter: MemberAdapter
    private var spools: List<SpoolEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpoolGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val manufacturer = intent.getStringExtra(EXTRA_MANUFACTURER) ?: return finish()
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return finish()
        val color = intent.getStringExtra(EXTRA_COLOR) ?: return finish()

        adapter = MemberAdapter { spool ->
            startActivity(
                Intent(this, SpoolDetailActivity::class.java)
                    .putExtra(SpoolDetailActivity.EXTRA_SPOOL_ID, spool.id),
            )
        }
        binding.memberList.layoutManager = LinearLayoutManager(this)
        binding.memberList.adapter = adapter
        binding.useOneButton.setOnClickListener { confirmUseOne() }

        lifecycleScope.launch {
            SpoolRepository.get(this@SpoolGroupActivity)
                .observeGroup(manufacturer, type, color)
                .collect { render(it) }
        }
    }

    private fun render(list: List<SpoolEntity>) {
        spools = list
        // The last one just left the group — used up, removed, or edited into a different
        // filament. There's nothing here to show any more.
        if (list.isEmpty()) {
            finish()
            return
        }
        val spec = list.last().toSpec()
        binding.groupTitle.text = SpoolDisplay.title(list.last())
        binding.groupCount.text = SpoolDisplay.spoolCount(list.size)
        binding.groupSpecs.text = SpoolDisplay.details(spec)
        binding.groupSwatch.setSwatchColor(spec.color)
        adapter.submitList(list)
    }

    private fun confirmUseOne() {
        val oldest = spools.firstOrNull() ?: return
        val added = DateFormat.getDateInstance().format(Date(oldest.addedAt))
        AlertDialog.Builder(this)
            .setTitle("Used one up?")
            .setMessage(
                "This logs the one you've had longest (added $added) and leaves " +
                    "${SpoolDisplay.spoolCount(spools.size - 1)} on the shelf.",
            )
            .setPositiveButton("Used one up") { _, _ ->
                lifecycleScope.launch {
                    SpoolRepository.get(this@SpoolGroupActivity).markEmpty(oldest)
                    Toast.makeText(
                        this@SpoolGroupActivity,
                        "Logged. One spool down.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

private class MemberAdapter(
    private val onClick: (SpoolEntity) -> Unit,
) : RecyclerView.Adapter<MemberAdapter.VH>() {

    private var items: List<SpoolEntity> = emptyList()

    fun submitList(list: List<SpoolEntity>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_spool_member, parent, false),
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], isNextUp = position == 0 && items.size > 1, onClick = onClick)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.memberTitle)
        private val subtitle: TextView = view.findViewById(R.id.memberSubtitle)

        fun bind(spool: SpoolEntity, isNextUp: Boolean, onClick: (SpoolEntity) -> Unit) {
            val added = DateFormat.getDateInstance().format(Date(spool.addedAt))
            title.text = if (isNextUp) "Added $added · next to be used" else "Added $added"
            // This list is where "which of these three is the one with the tags on it" gets
            // answered, so the tag state leads and the UID is a detail after it.
            subtitle.text = buildString {
                val uid = spool.tagUid
                if (uid == null) {
                    append("No tags on it yet")
                } else {
                    append(if (spool.source == SpoolSource.CUSTOM) "Own tags" else "Factory tag")
                    append(" · ")
                    append(uid.takeLast(8))
                    if (spool.tagsStale) append(" · tags out of date")
                }
            }
            itemView.setOnClickListener { onClick(spool) }
        }
    }
}
