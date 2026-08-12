package com.jamieduncan.acetag

import android.app.AlertDialog
import android.content.Intent
import android.nfc.Tag
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.jamieduncan.acetag.data.SpoolRepository
import com.jamieduncan.acetag.databinding.ActivityScanBinding
import kotlinx.coroutines.launch

/**
 * Reads one tag and routes. Three outcomes, no dialogs to disambiguate between them:
 *
 *  - **A tag we've recorded** (either sticker of a custom spool, or a scanned Anycubic tag) —
 *    open that spool. Scanning is a lookup, so this is the whole interaction.
 *  - **An unknown tag in Anycubic format** — a spool we don't have yet. Straight to
 *    [AddSpoolActivity] to confirm and add it.
 *  - **An unknown tag with no spool data** — a blank sticker or something unrelated. Offer to
 *    start a custom spool with it.
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
                result.spec != null -> addSpool(result)
                else -> offerCustomSpool()
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

    private fun offerCustomSpool() {
        // The sticker can't be carried forward as "tag 1 of 2" — writing needs the tag physically
        // present, and this tap is over by the time the next screen exists. The user taps it again
        // as sticker 1 in the write flow.
        AlertDialog.Builder(this)
            .setTitle("Blank tag")
            .setMessage(
                "There's no filament data on this tag. If it's a blank sticker, you can use it " +
                    "for a spool of your own — you'll write two, one for each side of the spool.",
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
