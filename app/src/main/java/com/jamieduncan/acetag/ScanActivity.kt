package com.jamieduncan.acetag

import android.app.AlertDialog
import android.content.Intent
import android.nfc.Tag
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.jamieduncan.acetag.data.SpoolEntity
import com.jamieduncan.acetag.data.SpoolRepository
import com.jamieduncan.acetag.databinding.ActivityScanBinding
import kotlinx.coroutines.launch

/**
 * Reads one tag and routes. No dialogs to disambiguate — the tag says which case it is:
 *
 *  - **A tag we've recorded** (either sticker of a custom spool, or a scanned Anycubic tag) —
 *    open that spool. Scanning is a lookup, so this is the whole interaction.
 *  - **An unknown tag we wrote** — a sticker not on any spool: its spool was used up, or its pair
 *    was moved elsewhere. Offer it to a spool that's waiting for tags. This is the fast path at
 *    the printer, where the adapter is in your hand and the phone is in your pocket: scan, tap a
 *    spool, write. Checked before the Anycubic case, because such a tag still decodes and would
 *    otherwise read as a spool you don't own yet.
 *  - **An unknown tag in Anycubic format** — a spool we don't have yet. Straight to
 *    [AddSpoolActivity] to confirm and add it.
 *  - **An unknown tag with no spool data** — a blank sticker or something unrelated. Offer it to a
 *    spool as well, or to start a new one.
 */
class ScanActivity : NfcActivity() {

    private lateinit var binding: ActivityScanBinding

    /** Guards against a second tap while we're already routing away from this screen. */
    private var handling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cancelButton.setOnClickListener { finish() }
        nfcUnavailableReason()?.let { binding.statusText.text = it }
    }

    override fun onResume() {
        super.onResume()
        handling = false
    }

    override fun onTagScanned(tag: Tag) {
        if (handling) return
        handling = true
        binding.statusText.text = "Reading…"

        val result = try {
            TagIo.read(tag)
        } catch (e: Exception) {
            binding.statusText.text = "Couldn't read that tag: ${e.message}\n\nTry again, holding it still."
            handling = false
            return
        }

        lifecycleScope.launch {
            val repo = SpoolRepository.get(this@ScanActivity)
            val known = repo.findByTagUid(result.uid)
            when {
                known != null -> openSpool(known.id)
                result.writtenHere -> offerToSpool(repo.untagged(), loose = true)
                result.spec != null -> addSpool(result)
                else -> offerToSpool(repo.untagged(), loose = false)
            }
        }
    }

    private fun openSpool(id: Long) {
        startActivity(
            Intent(this, SpoolDetailActivity::class.java)
                .putExtra(SpoolDetailActivity.EXTRA_SPOOL_ID, id),
        )
        finish()
    }

    private fun addSpool(result: TagIo.ReadResult) {
        startActivity(AddSpoolActivity.intent(this, result))
        finish()
    }

    /**
     * A sticker with no spool. Offers it to the spools that are waiting for tags, newest first,
     * with setting up a new spool as the last resort rather than the only one.
     *
     * The sticker itself can't be carried forward as "tag 1 of 2" — writing needs the tag
     * physically present, and this tap is over by the time the next screen exists. The user taps
     * it again as sticker 1 in the write flow.
     */
    private fun offerToSpool(candidates: List<SpoolEntity>, loose: Boolean) {
        if (candidates.isEmpty()) {
            offerNewSpool(loose)
            return
        }
        val labels = candidates.map { SpoolDisplay.title(it) } + "Set up a new spool…"
        AlertDialog.Builder(this)
            .setTitle("Put these tags on which spool?")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == candidates.size) {
                    startActivity(Intent(this, CustomSpoolActivity::class.java))
                } else {
                    startActivity(CustomSpoolActivity.writeIntent(this, candidates[which].id))
                }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun offerNewSpool(loose: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if (loose) "These tags aren't on a spool" else "Blank tag")
            .setMessage(
                if (loose) {
                    "You wrote these, but no spool in your inventory is wearing them — its spool " +
                        "was used up, or they were moved. Set up a spool and they'll be reused."
                } else {
                    "There's no filament data on this tag. If it's a blank sticker, you can use " +
                        "it for a spool of your own — you'll write two, one for each side."
                },
            )
            .setPositiveButton("Set up a spool") { _, _ ->
                startActivity(Intent(this, CustomSpoolActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
