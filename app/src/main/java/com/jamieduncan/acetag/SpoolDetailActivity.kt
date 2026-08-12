package com.jamieduncan.acetag

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jamieduncan.acetag.data.SpoolEntity
import com.jamieduncan.acetag.data.SpoolRepository
import com.jamieduncan.acetag.data.SpoolSource
import com.jamieduncan.acetag.databinding.ActivitySpoolDetailBinding
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * One spool, and everything you can do to it.
 *
 * The two ways a spool leaves the inventory are deliberately separate buttons, because they mean
 * opposite things to the history: using a spool up is a real event worth recording, while removing
 * one you entered by mistake has to leave no trace at all or it shows up as filament you never
 * bought. Don't merge them into one "delete".
 */
class SpoolDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SPOOL_ID = "spool_id"
    }

    private lateinit var binding: ActivitySpoolDetailBinding
    private var spoolId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpoolDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        spoolId = intent.getLongExtra(EXTRA_SPOOL_ID, -1)
        if (spoolId < 0) finish()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val spool = SpoolRepository.get(this@SpoolDetailActivity).getById(spoolId)
            if (spool == null) finish() else render(spool)
        }
    }

    private fun render(spool: SpoolEntity) {
        val spec = spool.toSpec()
        binding.detailTitle.text = SpoolDisplay.title(spool)
        binding.detailSwatch.setSwatchColor(spool.color)

        binding.detailAbrasive.text = SpoolDisplay.ABRASIVE_LABEL
        binding.detailAbrasive.visibility = if (spool.isAbrasive) View.VISIBLE else View.GONE

        val added = DateFormat.getDateInstance().format(Date(spool.addedAt))
        val origin = when (spool.source) {
            SpoolSource.ANYCUBIC -> "Added $added from the spool's own tag"
            SpoolSource.CUSTOM -> "Added $added, tags written here"
        }
        binding.detailStatus.text = origin
        binding.detailSpecs.text = SpoolDisplay.details(spec)

        binding.detailTags.text = when (spool.source) {
            SpoolSource.ANYCUBIC -> "Tag ${spool.tagUid}"
            SpoolSource.CUSTOM -> "Tag 1: ${spool.tagUid}\nTag 2: ${spool.tagUid2 ?: "—"}"
        }

        binding.staleCard.visibility = if (spool.tagsStale) View.VISIBLE else View.GONE
        binding.rewriteButton.visibility =
            if (spool.source == SpoolSource.CUSTOM) View.VISIBLE else View.GONE

        binding.editButton.setOnClickListener {
            startActivity(CustomSpoolActivity.editIntent(this, spoolId))
        }
        binding.rewriteButton.setOnClickListener {
            startActivity(CustomSpoolActivity.rewriteIntent(this, spoolId))
        }
        binding.emptyButton.setOnClickListener { confirmUsedUp(spool) }
        binding.deleteButton.setOnClickListener { confirmMistake(spool) }
    }

    private fun confirmUsedUp(spool: SpoolEntity) {
        AlertDialog.Builder(this)
            .setTitle("Used up?")
            .setMessage(
                "${SpoolDisplay.title(spool)} comes off your shelf and goes into your " +
                    "filament history, so you can see what you get through over time.",
            )
            .setPositiveButton("Used it up") { _, _ ->
                lifecycleScope.launch {
                    SpoolRepository.get(this@SpoolDetailActivity).markEmpty(spool)
                    toast("Logged. One spool down.")
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmMistake(spool: SpoolEntity) {
        AlertDialog.Builder(this)
            .setTitle("Remove this entry?")
            .setMessage(
                "Use this only if the spool shouldn't have been added — a typo, or a double scan. " +
                    "It's removed completely and won't count as filament you've used.\n\n" +
                    "If you finished the spool, go back and use \"I've used this spool up\" instead.",
            )
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    SpoolRepository.get(this@SpoolDetailActivity).deleteMistake(spool)
                    toast("Removed.")
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
