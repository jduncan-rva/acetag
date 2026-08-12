package com.jamieduncan.acetag

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.nfc.Tag
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.jamieduncan.acetag.data.SpoolEntity
import com.jamieduncan.acetag.data.SpoolRepository
import com.jamieduncan.acetag.data.SpoolSource
import com.jamieduncan.acetag.data.toSpool
import com.jamieduncan.acetag.databinding.ActivityCustomSpoolBinding
import kotlinx.coroutines.launch

/**
 * Workflow 2: a spool that didn't come with a tag, so we write our own.
 *
 * The ACE reads whichever side of the spool faces it, so a spool needs a sticker on **each side**.
 * Both carry identical data and a shared group ID. The write is all-or-nothing: nothing is saved
 * to the inventory until both stickers are written, because a half-tagged spool in the inventory
 * would claim to work in the printer when it only works one way up.
 *
 * Also handles editing an existing spool, since it's the same form and editing a spec is what
 * makes stickers go stale:
 *  - CREATE (no extras) — new spool, write both stickers, then save.
 *  - EDIT ([EXTRA_SPOOL_ID]) — change an existing spool's details.
 *  - REWRITE ([EXTRA_SPOOL_ID] + [EXTRA_REWRITE]) — straight into writing both stickers again.
 */
class CustomSpoolActivity : NfcActivity() {

    companion object {
        private const val EXTRA_SPOOL_ID = "spool_id"
        private const val EXTRA_REWRITE = "rewrite"

        fun editIntent(context: Context, spoolId: Long) =
            Intent(context, CustomSpoolActivity::class.java).putExtra(EXTRA_SPOOL_ID, spoolId)

        fun rewriteIntent(context: Context, spoolId: Long) =
            editIntent(context, spoolId).putExtra(EXTRA_REWRITE, true)
    }

    private enum class Step {
        /** Editing the form; taps on tags are ignored. */
        FORM,
        AWAITING_FIRST,
        AWAITING_SECOND,
        DONE,
    }

    private lateinit var binding: ActivityCustomSpoolBinding
    private lateinit var materials: List<String>

    /** Non-null in EDIT and REWRITE. */
    private var existing: SpoolEntity? = null
    private var rewriting = false

    private var step = Step.FORM
    private var groupId: ByteArray = SpoolTag.randomGroupId()
    private var firstUid: String? = null
    private var specBeingWritten: SpoolTag.Spec? = null

    /**
     * UIDs already spoken for, loaded before arming so the check at tap time is synchronous —
     * we can't go to the database and back while the tag is still in the field.
     */
    private var claimedUids: Set<String> = emptySet()

    private var pendingMatchHex: String? = null

    private val pickColor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val hex = result.data?.getStringExtra(ColorPickerActivity.EXTRA_COLOR_HEX)
        if (result.resultCode == RESULT_OK && hex != null) binding.colorInput.setText(hex)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomSpoolBinding.inflate(layoutInflater)
        setContentView(binding.root)

        materials = SpoolTag.SKUS.keys.toList()
        binding.typeInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, materials),
        )

        binding.colorInput.doOnTextChanged { text, _, _, _ -> onColorChanged(text?.toString()) }
        binding.cameraButton.setOnClickListener {
            pickColor.launch(Intent(this, ColorPickerActivity::class.java))
        }
        binding.matchButton.setOnClickListener { findBrandMatch() }
        binding.matchApplyButton.setOnClickListener {
            pendingMatchHex?.let { binding.colorInput.setText(it) }
            binding.matchCard.visibility = View.GONE
        }
        binding.matchDismissButton.setOnClickListener { binding.matchCard.visibility = View.GONE }
        binding.advancedToggle.setOnClickListener { toggleAdvanced() }
        binding.cancelButton.setOnClickListener { cancel() }

        // Backing out between the two stickers is the one moment worth interrupting: the first
        // sticker has already been written, and leaving now saves nothing.
        onBackPressedDispatcher.addCallback(this) {
            if (step == Step.AWAITING_SECOND) confirmStopHalfway() else finish()
        }

        val spoolId = intent.getLongExtra(EXTRA_SPOOL_ID, -1L)
        if (spoolId >= 0) setUpForExisting(spoolId) else setUpForNew()
    }

    private fun setUpForNew() {
        binding.typeInput.setOnItemClickListener { _, _, position, _ ->
            applyMaterialDefaults(materials[position])
        }
        binding.typeInput.setText(materials.first(), false)
        applyMaterialDefaults(materials.first())
        binding.primaryButton.setOnClickListener { startWriting() }
    }

    private fun setUpForExisting(spoolId: Long) {
        rewriting = intent.getBooleanExtra(EXTRA_REWRITE, false)
        binding.primaryButton.isEnabled = false
        lifecycleScope.launch {
            val spool = SpoolRepository.get(this@CustomSpoolActivity).getById(spoolId)
            if (spool == null) {
                finish()
                return@launch
            }
            existing = spool
            groupId = spool.groupId?.hexToBytes() ?: SpoolTag.randomGroupId()
            fillForm(spool)
            binding.primaryButton.isEnabled = true

            if (rewriting) {
                binding.headingText.text = "Rewrite this spool's tags"
                binding.statusText.text =
                    "You'll write both stickers again, one for each side of the spool."
                binding.primaryButton.text = "Write the tags"
                binding.primaryButton.setOnClickListener { startWriting() }
            } else {
                binding.headingText.text = "Edit this spool"
                binding.statusText.text = "Changing the filament details won't touch the stickers " +
                    "already on the spool — you'll be offered a rewrite if they stop matching."
                binding.primaryButton.text = "Save changes"
                binding.primaryButton.setOnClickListener { saveEdit() }
            }
        }
    }

    // ---------------------------------------------------------------- form

    private fun fillForm(spool: SpoolEntity) {
        binding.typeInput.setText(spool.type, false)
        // Only re-apply defaults on an explicit material change; an existing spool's own numbers win.
        binding.typeInput.setOnItemClickListener { _, _, position, _ ->
            applyMaterialDefaults(materials[position])
        }
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

    private fun applyMaterialDefaults(type: String) {
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

    private fun toggleAdvanced() {
        val showing = binding.advancedSection.visibility == View.VISIBLE
        binding.advancedSection.visibility = if (showing) View.GONE else View.VISIBLE
        binding.advancedToggle.text =
            if (showing) "Adjust temperatures and size" else "Hide temperatures and size"
    }

    private fun onColorChanged(text: String?) {
        binding.matchCard.visibility = View.GONE
        val hex = normalizeHex(text)
        binding.matchButton.isEnabled = hex != null
        hex?.let { binding.colorSwatch.setSwatchColor(it) }
    }

    private fun normalizeHex(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        val hex = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return if (hex.matches(Regex("^#[0-9a-fA-F]{6}$"))) hex else null
    }

    private fun findBrandMatch() {
        val hex = normalizeHex(binding.colorInput.text?.toString()) ?: return
        val material = binding.typeInput.text.toString().trim()
        binding.matchButton.isEnabled = false
        binding.matchButton.text = "Searching…"
        lifecycleScope.launch {
            val match = FilamentColorMatch.fetchBestMatch(hex, material.ifBlank { null })
            binding.matchButton.isEnabled = true
            binding.matchButton.text = "Find a matching brand colour"
            if (match == null) {
                toast("No match found — check your connection and try again.")
                return@launch
            }
            val matchedHex = "#${match.matchedHex}"
            pendingMatchHex = matchedHex
            binding.matchSwatch.setSwatchColor(matchedHex)
            binding.matchText.text = "${match.manufacturer} — ${match.colorName} (${match.filamentType})"
            binding.matchCard.visibility = View.VISIBLE
        }
    }

    /** Reads the form, flagging bad fields in place. Null means don't proceed. */
    private fun readSpec(): SpoolTag.Spec? {
        binding.colorInputLayout.error = null
        binding.diameterInputLayout.error = null
        binding.nozzleMinInputLayout.error = null
        binding.nozzleMaxInputLayout.error = null

        var bad = false
        fun int(field: com.google.android.material.textfield.TextInputEditText) =
            field.text?.toString()?.trim()?.toIntOrNull()

        val color = normalizeHex(binding.colorInput.text?.toString())
        if (color == null) {
            binding.colorInputLayout.error = "Enter a 6-digit hex colour, e.g. #89a84f"
            bad = true
        }
        val diameter = binding.diameterInput.text?.toString()?.trim()?.toDoubleOrNull()
        if (diameter == null) {
            binding.diameterInputLayout.error = "Enter a diameter, e.g. 1.75"
            bad = true
        }
        val nozzleMin = int(binding.nozzleMinInput)
        if (nozzleMin == null) {
            binding.nozzleMinInputLayout.error = "Required"
            bad = true
        }
        val nozzleMax = int(binding.nozzleMaxInput)
        if (nozzleMax == null) {
            binding.nozzleMaxInputLayout.error = "Required"
            bad = true
        }

        if (bad) {
            binding.advancedSection.visibility = View.VISIBLE
            toast("Check the highlighted fields")
            return null
        }

        return SpoolTag.Spec(
            type = binding.typeInput.text.toString().trim().ifBlank { materials.first() },
            manufacturer = binding.manufacturerInput.text?.toString()?.trim().orEmpty(),
            color = color!!,
            nozzleMin = nozzleMin!!,
            nozzleMax = nozzleMax!!,
            bedMin = int(binding.bedMinInput) ?: 0,
            bedMax = int(binding.bedMaxInput) ?: 0,
            speedMin = int(binding.speedMinInput) ?: 0,
            speedMax = int(binding.speedMaxInput) ?: 0,
            diameterMm = diameter!!,
            lengthM = int(binding.lengthInput) ?: 0,
            weightG = int(binding.weightInput) ?: 0,
        )
    }

    // ---------------------------------------------------------------- editing

    private fun saveEdit() {
        val spool = existing ?: return
        val spec = readSpec() ?: return
        val changed = spool.specDiffersFrom(spec)
        lifecycleScope.launch {
            val repo = SpoolRepository.get(this@CustomSpoolActivity)
            val stale = spool.tagsStale || (changed && spool.source == SpoolSource.CUSTOM)
            repo.updateSpool(spool.withSpec(spec).copy(tagsStale = stale))

            if (changed && spool.source == SpoolSource.CUSTOM) {
                AlertDialog.Builder(this@CustomSpoolActivity)
                    .setTitle("Saved — but the stickers are out of date")
                    .setMessage(
                        "The two stickers on this spool still say what it used to. Rewrite them " +
                            "now, or do it later from the spool's page.",
                    )
                    .setPositiveButton("Rewrite now") { _, _ ->
                        existing = spool.withSpec(spec).copy(tagsStale = true)
                        rewriting = true
                        startWriting()
                    }
                    .setNegativeButton("Later") { _, _ -> finish() }
                    .setOnCancelListener { finish() }
                    .show()
            } else {
                toast("Saved.")
                finish()
            }
        }
    }

    // ---------------------------------------------------------------- writing

    private fun startWriting() {
        nfcUnavailableReason()?.let {
            toast(it)
            return
        }
        val spec = readSpec() ?: return
        specBeingWritten = spec

        lifecycleScope.launch {
            val repo = SpoolRepository.get(this@CustomSpoolActivity)
            // A rewrite lands on the spool's own stickers, so those two don't count as taken.
            val own = setOfNotNull(existing?.tagUid, existing?.tagUid2)
            claimedUids = repo.allTagUids().toSet() - own

            firstUid = null
            step = Step.AWAITING_FIRST
            binding.primaryButton.visibility = View.GONE
            binding.formScroll.visibility = View.GONE
            binding.writePanel.visibility = View.VISIBLE
            setStatus("Hold sticker 1 of 2 against the back of the phone.")
        }
    }

    override fun onTagScanned(tag: Tag) {
        val spec = specBeingWritten ?: return
        when (step) {
            Step.FORM, Step.DONE -> return
            Step.AWAITING_FIRST -> writeFirst(tag, spec)
            Step.AWAITING_SECOND -> writeSecond(tag, spec)
        }
    }

    private fun writeFirst(tag: Tag, spec: SpoolTag.Spec) {
        val uid = TagIo.uidOf(tag)
        if (uid in claimedUids) {
            setStatus("That sticker already belongs to another spool. Use a different one for sticker 1.")
            return
        }
        try {
            TagIo.write(tag, spec, groupId)
        } catch (e: Exception) {
            setStatus("Sticker 1 didn't take: ${e.message}\n\nHold it flat and steady, and try again.")
            return
        }
        firstUid = uid
        step = Step.AWAITING_SECOND
        setStatus("Sticker 1 done. Now hold sticker 2 of 2 — the one for the other side of the spool.")
    }

    private fun writeSecond(tag: Tag, spec: SpoolTag.Spec) {
        val uid = TagIo.uidOf(tag)
        if (uid == firstUid) {
            setStatus("That's the sticker you just wrote. Use the second one, for the other side of the spool.")
            return
        }
        if (uid in claimedUids) {
            setStatus("That sticker already belongs to another spool. Use a different one for sticker 2.")
            return
        }
        try {
            TagIo.write(tag, spec, groupId)
        } catch (e: Exception) {
            setStatus("Sticker 2 didn't take: ${e.message}\n\nHold it flat and steady, and try again.")
            return
        }
        step = Step.DONE
        save(spec, firstUid!!, uid)
    }

    private fun save(spec: SpoolTag.Spec, uid1: String, uid2: String) {
        lifecycleScope.launch {
            val repo = SpoolRepository.get(this@CustomSpoolActivity)
            val spool = existing
            if (spool == null) {
                repo.addSpool(
                    spec.toSpool(
                        source = SpoolSource.CUSTOM,
                        tagUid = uid1,
                        tagUid2 = uid2,
                        groupId = groupId.toHexString(),
                    ),
                )
                toast("Both tags written. ${SpoolDisplay.title(spec)} added to your inventory.")
            } else {
                // markTagsFresh writes the whole row, so the edited spec rides along with it.
                repo.markTagsFresh(spool.withSpec(spec), uid1, uid2, groupId.toHexString())
                toast("Both tags rewritten.")
            }
            finish()
        }
    }

    private fun cancel() {
        if (step == Step.AWAITING_SECOND) confirmStopHalfway() else finish()
    }

    private fun confirmStopHalfway() {
        AlertDialog.Builder(this)
            .setTitle("Stop after one sticker?")
            .setMessage(
                "This spool needs a tag on each side, so nothing has been saved yet. " +
                    "Sticker 1 has data on it but no spool — you can reuse it next time.",
            )
            .setPositiveButton("Stop") { _, _ -> finish() }
            .setNegativeButton("Keep going", null)
            .show()
    }

    /** Goes to the form's subtitle before the write starts, and to the write panel during it. */
    private fun setStatus(message: String) {
        if (step == Step.FORM) {
            binding.statusText.text = message
        } else {
            binding.writeStepText.text =
                if (step == Step.AWAITING_FIRST) "Sticker 1 of 2" else "Sticker 2 of 2"
            binding.writeStatusText.text = message
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
