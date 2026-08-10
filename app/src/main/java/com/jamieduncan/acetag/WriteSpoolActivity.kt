package com.jamieduncan.acetag

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.jamieduncan.acetag.data.AppDatabase
import com.jamieduncan.acetag.data.SpoolEntity
import com.jamieduncan.acetag.databinding.ActivityWriteSpoolBinding
import kotlinx.coroutines.launch

/**
 * Handles three flows, chosen by which intent extras are present:
 *  - CREATE (no extras): blank form, arm + tap writes tag A, then tag B; saves a new
 *    inventory row after the first successful write.
 *  - IMPORT (EXTRA_IMPORT_* extras from ReadTagActivity): form pre-filled from a tag that
 *    was just read and didn't match anything in inventory. The tag already has valid data,
 *    so "Save" just logs it; no NFC write needed unless writing a replacement/second tag.
 *  - REPRINT (EXTRA_REPRINT_SPOOL_ID): form pre-filled from an existing inventory row, for
 *    writing a replacement tag when one is lost or damaged. Updates that row's tag UID
 *    instead of creating a new one.
 */
class WriteSpoolActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMPORT_UID = "import_uid"
        const val EXTRA_IMPORT_GROUP_ID = "import_group_id"
        const val EXTRA_IMPORT_TYPE = "import_type"
        const val EXTRA_IMPORT_MANUFACTURER = "import_manufacturer"
        const val EXTRA_IMPORT_COLOR = "import_color"
        const val EXTRA_IMPORT_NOZZLE_MIN = "import_nozzle_min"
        const val EXTRA_IMPORT_NOZZLE_MAX = "import_nozzle_max"
        const val EXTRA_IMPORT_BED_MIN = "import_bed_min"
        const val EXTRA_IMPORT_BED_MAX = "import_bed_max"
        const val EXTRA_IMPORT_SPEED_MIN = "import_speed_min"
        const val EXTRA_IMPORT_SPEED_MAX = "import_speed_max"
        const val EXTRA_IMPORT_DIAMETER = "import_diameter"
        const val EXTRA_IMPORT_LENGTH = "import_length"
        const val EXTRA_IMPORT_WEIGHT = "import_weight"

        const val EXTRA_REPRINT_SPOOL_ID = "reprint_spool_id"
    }

    private lateinit var binding: ActivityWriteSpoolBinding
    private var nfcAdapter: NfcAdapter? = null
    private var armed = false
    private var lastWrittenSpec: SpoolTag.Spec? = null

    /** id of the inventory row this write session is tied to, once one exists */
    private var spoolId: Long? = null
    private var importUid: String? = null
    private var importGroupId: String? = null
    private var reprintSpoolId: Long? = null

    /** Group ID written into both tags of a spool so a future read can pair them deterministically.
     *  Fresh random for CREATE; carried over from the existing row for REPRINT; null for IMPORT
     *  (an already-existing tag we didn't write has no group ID to carry). */
    private var groupId: ByteArray? = null

    private val pickColor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val hex = result.data?.getStringExtra(ColorPickerActivity.EXTRA_COLOR_HEX)
        if (result.resultCode == RESULT_OK && hex != null) {
            binding.colorInput.setText(hex)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWriteSpoolBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val types = SpoolTag.SKUS.keys.toList()
        binding.typeInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, types),
        )
        binding.typeInput.setOnItemClickListener { _, _, position, _ ->
            if (importUid == null && reprintSpoolId == null) applyDefaults(types[position])
        }

        binding.colorInput.doOnTextChanged { text, _, _, _ -> updateSwatch(text) }
        binding.cameraButton.setOnClickListener {
            pickColor.launch(Intent(this, ColorPickerActivity::class.java))
        }

        binding.writeSecondButton.setOnClickListener {
            lastWrittenSpec?.let {
                armed = true
                setStatus("Armed for the next tag. Hold it to the back of the phone.")
            }
        }

        setUpForMode(types)

        if (nfcAdapter == null) {
            setStatus("This device has no NFC hardware.")
            binding.writeButton.isEnabled = false
        } else if (nfcAdapter?.isEnabled == false) {
            setStatus("NFC is off. Enable it in system settings, then come back.")
        }
    }

    private fun setUpForMode(types: List<String>) {
        importUid = intent.getStringExtra(EXTRA_IMPORT_UID)
        val reprintId = intent.getLongExtra(EXTRA_REPRINT_SPOOL_ID, -1L)

        when {
            importUid != null -> {
                importGroupId = intent.getStringExtra(EXTRA_IMPORT_GROUP_ID)
                val importType = intent.getStringExtra(EXTRA_IMPORT_TYPE)
                binding.typeInput.setText(importType ?: types.first(), false)
                binding.manufacturerInput.setText(intent.getStringExtra(EXTRA_IMPORT_MANUFACTURER))
                binding.colorInput.setText(intent.getStringExtra(EXTRA_IMPORT_COLOR))
                binding.nozzleMinInput.setText(intent.getIntExtra(EXTRA_IMPORT_NOZZLE_MIN, 0).toString())
                binding.nozzleMaxInput.setText(intent.getIntExtra(EXTRA_IMPORT_NOZZLE_MAX, 0).toString())
                binding.bedMinInput.setText(intent.getIntExtra(EXTRA_IMPORT_BED_MIN, 0).toString())
                binding.bedMaxInput.setText(intent.getIntExtra(EXTRA_IMPORT_BED_MAX, 0).toString())
                val speedMin = intent.getIntExtra(EXTRA_IMPORT_SPEED_MIN, 0)
                val speedMax = intent.getIntExtra(EXTRA_IMPORT_SPEED_MAX, 0)
                binding.speedMinInput.setText(if (speedMin > 0) speedMin.toString() else "")
                binding.speedMaxInput.setText(if (speedMax > 0) speedMax.toString() else "")
                binding.diameterInput.setText(intent.getDoubleExtra(EXTRA_IMPORT_DIAMETER, 1.75).toString())
                binding.lengthInput.setText(intent.getIntExtra(EXTRA_IMPORT_LENGTH, 0).toString())
                binding.weightInput.setText(intent.getIntExtra(EXTRA_IMPORT_WEIGHT, 0).toString())

                binding.writeButton.text = "💾 Save to Inventory"
                binding.statusText.text =
                    "This tag was already written (not by this app, or previously). Review the details and save it to your inventory — no need to rewrite the tag."
                binding.writeButton.setOnClickListener { saveImportedSpool() }
            }
            reprintId >= 0 -> {
                reprintSpoolId = reprintId
                binding.writeButton.text = "📡 Arm for Write"
                setStatus("Loading spool...")
                lifecycleScope.launch {
                    val spool = AppDatabase.get(this@WriteSpoolActivity).spoolDao().getById(reprintId)
                    if (spool == null) {
                        setStatus("Could not find that spool.")
                        return@launch
                    }
                    fillForm(spool, types)
                    groupId = spool.groupId?.hexToBytes()
                    setStatus("Reprinting a tag for this spool. Tap Arm for Write, then hold the new tag to the phone.")
                }
                binding.writeButton.setOnClickListener { armForWrite() }
            }
            else -> {
                groupId = SpoolTag.randomGroupId()
                binding.typeInput.setText(types.first(), false)
                applyDefaults(types.first())
                binding.writeButton.text = "📡 Arm for Write"
                setStatus("Fill in the spool details, then tap Arm for Write and hold a tag to the back of the phone.")
                binding.writeButton.setOnClickListener { armForWrite() }
            }
        }
    }

    private fun fillForm(spool: SpoolEntity, types: List<String>) {
        binding.typeInput.setText(if (spool.type in types) spool.type else types.first(), false)
        binding.manufacturerInput.setText(spool.manufacturer)
        binding.colorInput.setText(spool.color)
        binding.nozzleMinInput.setText(spool.nozzleMin.toString())
        binding.nozzleMaxInput.setText(spool.nozzleMax.toString())
        binding.bedMinInput.setText(spool.bedMin.toString())
        binding.bedMaxInput.setText(spool.bedMax.toString())
        binding.speedMinInput.setText(if (spool.speedMin > 0) spool.speedMin.toString() else "")
        binding.speedMaxInput.setText(if (spool.speedMax > 0) spool.speedMax.toString() else "")
        binding.diameterInput.setText(spool.diameterMm.toString())
        binding.lengthInput.setText(spool.lengthM.toString())
        binding.weightInput.setText(spool.weightG.toString())
    }

    private fun armForWrite() {
        if (readSpecOrShowError() != null) {
            armed = true
            binding.writeSecondButton.visibility = View.GONE
            setStatus("Armed. Hold the NFC tag to the back of the phone now.")
        }
    }

    private fun saveImportedSpool() {
        val spec = readSpecOrShowError() ?: return
        val uid = importUid ?: return
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@WriteSpoolActivity).spoolDao()
            val id = dao.insert(spec.toEntity(tagUidA = uid, groupId = importGroupId))
            spoolId = id
            lastWrittenSpec = spec
            binding.writeButton.isEnabled = false
            binding.writeSecondButton.text = "Write Replacement/Second Tag"
            binding.writeSecondButton.visibility = View.VISIBLE
            setStatus("✓ Saved to inventory. If this spool has a second tag, tap below to write it identically.")
        }
    }

    private fun applyDefaults(type: String) {
        val d = SpoolTag.MATERIAL_DEFAULTS[type] ?: return
        binding.nozzleMinInput.setText(d.nozzleMin.toString())
        binding.nozzleMaxInput.setText(d.nozzleMax.toString())
        binding.bedMinInput.setText(d.bedMin.toString())
        binding.bedMaxInput.setText(d.bedMax.toString())
        binding.speedMinInput.setText(if (d.speedMin > 0) d.speedMin.toString() else "")
        binding.speedMaxInput.setText(if (d.speedMax > 0) d.speedMax.toString() else "")
        binding.diameterInput.setText(d.diameterMm.toString())
        binding.lengthInput.setText(d.lengthM.toString())
        binding.weightInput.setText(d.weightG.toString())
    }

    private fun updateSwatch(text: CharSequence?) {
        val hex = text?.toString()?.trim().orEmpty()
        val normalized = if (hex.startsWith("#")) hex else "#$hex"
        if (!normalized.matches(Regex("^#[0-9a-fA-F]{6}$"))) return
        val bg = (binding.colorSwatch.background as GradientDrawable).mutate() as GradientDrawable
        bg.setColor(Color.parseColor(normalized))
        binding.colorSwatch.background = bg
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE,
        )
        val techLists = arrayOf(arrayOf(NfcA::class.java.name))
        val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
        adapter.enableForegroundDispatch(this, pendingIntent, filters, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!armed) {
            setStatus("Tag detected but not armed. Tap Arm for Write first.")
            return
        }
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) {
            setStatus("No tag data in intent.")
            return
        }
        val spec = readSpecOrShowError() ?: return
        writeTag(tag, spec)
    }

    private fun writeTag(tag: Tag, spec: SpoolTag.Spec) {
        val nfcA = NfcA.get(tag)
        if (nfcA == null) {
            setStatus("This tag doesn't support NfcA — can't write to it.")
            return
        }
        val uid = tag.id.joinToString("") { "%02x".format(it) }
        try {
            nfcA.connect()
            val built = SpoolTag.buildTag(spec, groupId)
            for (i in 0 until SpoolTag.PAGE_COUNT) {
                val page = SpoolTag.START_PAGE + i
                Type2Tag.writePage(nfcA, page, built.pages[i])
            }
            armed = false
            lastWrittenSpec = spec
            binding.writeSecondButton.text = "Write Second Tag (same spec)"
            binding.writeSecondButton.visibility = View.VISIBLE
            setStatus("✓ Wrote ${spec.type} \"${spec.color}\" to tag. Remember: each spool needs two tags written identically.")
            persistAfterWrite(spec, uid)
        } catch (e: Exception) {
            setStatus("Write failed: ${e.message}. Try holding the tag steady and tap Arm again.")
        } finally {
            try { nfcA.close() } catch (_: Exception) {}
        }
    }

    private fun persistAfterWrite(spec: SpoolTag.Spec, uid: String) {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@WriteSpoolActivity).spoolDao()
            val existingId = spoolId ?: reprintSpoolId
            if (existingId == null) {
                spoolId = dao.insert(spec.toEntity(tagUidA = uid, groupId = groupId?.toHexString()))
            } else {
                val existing = dao.getById(existingId) ?: return@launch
                val updated = when {
                    existing.tagUidA == null -> existing.copy(tagUidA = uid)
                    existing.tagUidB == null -> existing.copy(tagUidB = uid)
                    else -> existing.copy(tagUidA = uid) // replacing a lost/damaged tag
                }
                dao.update(updated.mergeSpec(spec))
                spoolId = existingId
            }
        }
    }

    private fun SpoolTag.Spec.toEntity(
        tagUidA: String? = null,
        tagUidB: String? = null,
        groupId: String? = null,
    ) = SpoolEntity(
        type = type,
        manufacturer = manufacturer,
        color = color,
        nozzleMin = nozzleMin,
        nozzleMax = nozzleMax,
        bedMin = bedMin,
        bedMax = bedMax,
        speedMin = speedMin,
        speedMax = speedMax,
        diameterMm = diameterMm,
        lengthM = lengthM,
        weightG = weightG,
        tagUidA = tagUidA,
        tagUidB = tagUidB,
        groupId = groupId,
        createdAt = System.currentTimeMillis(),
    )

    private fun SpoolEntity.mergeSpec(spec: SpoolTag.Spec) = copy(
        type = spec.type,
        manufacturer = spec.manufacturer,
        color = spec.color,
        nozzleMin = spec.nozzleMin,
        nozzleMax = spec.nozzleMax,
        bedMin = spec.bedMin,
        bedMax = spec.bedMax,
        speedMin = spec.speedMin,
        speedMax = spec.speedMax,
        diameterMm = spec.diameterMm,
        lengthM = spec.lengthM,
        weightG = spec.weightG,
    )

    private fun readSpecOrShowError(): SpoolTag.Spec? {
        clearErrors()
        fun text(id: Int) = findViewById<EditText>(id).text.toString().trim()
        fun intOr(id: Int, default: Int): Int = text(id).toIntOrNull() ?: default

        val type = binding.typeInput.text.toString().trim().ifBlank { SpoolTag.SKUS.keys.first() }

        var hasError = false
        val color = binding.colorInput.text.toString().trim()
        val normalizedColor = if (color.startsWith("#")) color else "#$color"
        if (!normalizedColor.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
            binding.colorInputLayout.error = "Enter a 6-digit hex color, e.g. #89a84f"
            hasError = true
        }

        val diameter = binding.diameterInput.text.toString().trim().toDoubleOrNull()
        if (diameter == null) {
            binding.diameterInputLayout.error = "Enter a valid diameter"
            hasError = true
        }

        val nozzleMin = text(R.id.nozzleMinInput).toIntOrNull()
        val nozzleMax = text(R.id.nozzleMaxInput).toIntOrNull()
        if (nozzleMin == null) {
            binding.nozzleMinInputLayout.error = "Required"
            hasError = true
        }
        if (nozzleMax == null) {
            binding.nozzleMaxInputLayout.error = "Required"
            hasError = true
        }

        if (hasError) {
            toast("Fix the highlighted fields")
            return null
        }

        return SpoolTag.Spec(
            type = type,
            manufacturer = binding.manufacturerInput.text.toString().trim().ifBlank { "AC" },
            color = normalizedColor,
            nozzleMin = nozzleMin!!,
            nozzleMax = nozzleMax!!,
            bedMin = intOr(R.id.bedMinInput, 0),
            bedMax = intOr(R.id.bedMaxInput, 0),
            speedMin = intOr(R.id.speedMinInput, 0),
            speedMax = intOr(R.id.speedMaxInput, 0),
            diameterMm = diameter!!,
            lengthM = intOr(R.id.lengthInput, 0),
            weightG = intOr(R.id.weightInput, 0),
        )
    }

    private fun clearErrors() {
        binding.colorInputLayout.error = null
        binding.diameterInputLayout.error = null
        binding.nozzleMinInputLayout.error = null
        binding.nozzleMaxInputLayout.error = null
    }

    private fun setStatus(message: String) {
        binding.statusText.text = message
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
