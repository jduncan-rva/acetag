package com.jamieduncan.acetag

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jamieduncan.acetag.data.AppDatabase
import com.jamieduncan.acetag.databinding.ActivityReadTagBinding
import kotlinx.coroutines.launch

class ReadTagActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReadTagBinding
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadTagBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        binding.cancelButton.setOnClickListener { finish() }

        if (nfcAdapter == null) {
            binding.statusText.text = "This device has no NFC hardware."
        } else if (nfcAdapter?.isEnabled == false) {
            binding.statusText.text = "NFC is off. Enable it in system settings, then come back."
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        val techLists = arrayOf(arrayOf(MifareUltralight::class.java.name))
        val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
        adapter.enableForegroundDispatch(this, pendingIntent, filters, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) {
            binding.statusText.text = "No tag data in intent."
            return
        }
        readTag(tag)
    }

    private fun readTag(tag: Tag) {
        val ultralight = MifareUltralight.get(tag)
        if (ultralight == null) {
            binding.statusText.text = "This tag is not a MIFARE Ultralight / NTAG21x chip."
            return
        }
        val uid = tag.id.joinToString("") { "%02x".format(it) }
        try {
            ultralight.connect()
            val raw = ByteArray(SpoolTag.PAGE_COUNT * 4)
            var offset = SpoolTag.START_PAGE
            var written = 0
            while (written < raw.size) {
                val chunk = ultralight.readPages(offset)
                val toCopy = minOf(chunk.size, raw.size - written)
                System.arraycopy(chunk, 0, raw, written, toCopy)
                written += toCopy
                offset += 4
            }
            val pages = SpoolTag.Pages.fromBytes(raw)
            val spec = SpoolTag.decode(pages)
            handleResult(uid, spec)
        } catch (e: Exception) {
            binding.statusText.text = "Read failed: ${e.message}"
        } finally {
            try { ultralight.close() } catch (_: Exception) {}
        }
    }

    private fun handleResult(uid: String, spec: SpoolTag.Spec?) {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@ReadTagActivity).spoolDao()
            val existing = dao.findByTagUid(uid)
            if (existing != null) {
                startActivity(
                    Intent(this@ReadTagActivity, SpoolDetailActivity::class.java)
                        .putExtra(SpoolDetailActivity.EXTRA_SPOOL_ID, existing.id),
                )
                finish()
                return@launch
            }

            if (spec == null) {
                binding.statusText.text =
                    "This tag isn't in Anycubic spool format (or is blank). Nothing to import."
                return@launch
            }

            val candidates = dao.findMatchingWithOpenSlot(
                type = spec.type,
                manufacturer = spec.manufacturer,
                color = spec.color,
                nozzleMin = spec.nozzleMin,
                nozzleMax = spec.nozzleMax,
                bedMin = spec.bedMin,
                bedMax = spec.bedMax,
                diameterMm = spec.diameterMm,
                weightG = spec.weightG,
            )

            if (candidates.size == 1) {
                val match = candidates.first()
                AlertDialog.Builder(this@ReadTagActivity)
                    .setTitle("Matching spool found")
                    .setMessage("This looks like the second tag for ${match.manufacturer} ${match.type} (${match.color}). Attach it to that spool?")
                    .setPositiveButton("Attach") { _, _ ->
                        lifecycleScope.launch {
                            dao.update(match.copy(tagUidB = uid))
                            startActivity(
                                Intent(this@ReadTagActivity, SpoolDetailActivity::class.java)
                                    .putExtra(SpoolDetailActivity.EXTRA_SPOOL_ID, match.id),
                            )
                            finish()
                        }
                    }
                    .setNegativeButton("Import as new") { _, _ -> launchImport(uid, spec) }
                    .setCancelable(false)
                    .show()
            } else {
                launchImport(uid, spec)
            }
        }
    }

    private fun launchImport(uid: String, spec: SpoolTag.Spec) {
        startActivity(
            Intent(this, WriteSpoolActivity::class.java)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_UID, uid)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_TYPE, spec.type)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_MANUFACTURER, spec.manufacturer)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_COLOR, spec.color)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_NOZZLE_MIN, spec.nozzleMin)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_NOZZLE_MAX, spec.nozzleMax)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_BED_MIN, spec.bedMin)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_BED_MAX, spec.bedMax)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_SPEED_MIN, spec.speedMin)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_SPEED_MAX, spec.speedMax)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_DIAMETER, spec.diameterMm)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_LENGTH, spec.lengthM)
                .putExtra(WriteSpoolActivity.EXTRA_IMPORT_WEIGHT, spec.weightG),
        )
        finish()
    }
}
