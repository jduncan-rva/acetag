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
 * Writing is optional, though. Filament under 1 kg can't reach the ACE's reader on its own and has
 * to sit on an adapter; a refill has no spool at all. In both cases one pair of stickers serves
 * whichever filament is mounted at the time, so a spool can be added now and tagged later, when
 * it's the one going in the printer. Hence "Just add it to my inventory" beside the write button:
 * the choice is offered at the only moment it means anything, and the spool is real either way.
 *
 * Also handles editing an existing spool, since it's the same form and editing a spec is what
 * makes stickers go stale:
 *  - CREATE (no extras) — new spool, write both stickers, or add it untagged.
 *  - EDIT ([EXTRA_SPOOL_ID]) — change an existing spool's details.
 *  - WRITE ([EXTRA_SPOOL_ID] + [EXTRA_WRITE]) — straight into writing both stickers: a rewrite, or
 *    a first set of tags for a spool that was added without any.
 */
class CustomSpoolActivity : NfcActivity() {

    companion object {
        private const val EXTRA_SPOOL_ID = "spool_id"
        private const val EXTRA_WRITE = "write"

        fun editIntent(context: Context, spoolId: Long) =
            Intent(context, CustomSpoolActivity::class.java).putExtra(EXTRA_SPOOL_ID, spoolId)

        /** Straight into the write flow for a spool already in the inventory. */
        fun writeIntent(context: Context, spoolId: Long) =
            editIntent(context, spoolId).putExtra(EXTRA_WRITE, true)
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

    /** Non-null in EDIT and WRITE. */
    private var existing: SpoolEntity? = null
    private var writingExisting = false

    private var step = Step.FORM
    private var groupId: ByteArray = SpoolTag.randomGroupId()
    private var firstUid: String? = null
    private var specBeingWritten: SpoolTag.Spec? = null

    /**
     * Which spool currently wears each recorded sticker, loaded before arming so a tap can be
     * answered synchronously — we can't go to the database and back while the tag is in the field.
     */
    private var owners: Map<String, SpoolEntity> = emptyMap()

    /**
     * Spools the user has agreed to take stickers off, keyed by row id. A tag stays in the field
     * for a moment, so the confirmation can't be answered while it's still readable — the dialog
     * asks, and the *next* tap of that sticker goes through. Approving is per spool, not per
     * sticker, so the second sticker of the same pair never asks again.
     */
    private val approvedMoves = mutableSetOf<Long>()

    /** Set while the form is being populated in code, so weight doesn't clobber a stored length. */
    private var fillingForm = false

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

        materials = FilamentMaterial.BASES
        binding.typeInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, materials),
        )
        binding.finishInput.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                FilamentMaterial.FINISH_LABELS,
            ),
        )

        binding.colorInput.doOnTextChanged { text, _, _, _ -> onColorChanged(text?.toString()) }
        binding.weightInput.doOnTextChanged { text, _, _, _ -> onWeightChanged(text?.toString()) }
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
            onMaterialChanged()
        }
        binding.finishInput.setOnItemClickListener { _, _, _, _ -> onMaterialChanged() }
        binding.typeInput.setText(materials.first(), false)
        binding.finishInput.setText(FilamentMaterial.Finish.NONE.label, false)
        applyMaterialDefaults(materials.first())
        onMaterialChanged()
        binding.primaryButton.setOnClickListener { startWriting() }

        // The spool is real whether or not it has stickers on it, so adding one without writing is
        // an ordinary thing to do, not a fallback. It's offered here rather than as a third button
        // on the inventory screen because this is the only point where the difference is legible.
        binding.secondaryButton.visibility = View.VISIBLE
        binding.secondaryButton.setOnClickListener { saveWithoutTags() }
    }

    private fun setUpForExisting(spoolId: Long) {
        writingExisting = intent.getBooleanExtra(EXTRA_WRITE, false)
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

            if (writingExisting) {
                if (spool.hasTags) {
                    binding.headingText.text = "Rewrite this spool's tags"
                    binding.statusText.text =
                        "You'll write both stickers again, one for each side of the spool."
                } else {
                    binding.headingText.text = "Put the tags on this spool"
                    binding.statusText.text =
                        "Hold each sticker against the phone in turn — one for each side. If " +
                        "they're on another spool right now, you'll be asked before they move."
                }
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

    private fun fillForm(spool: SpoolEntity) = withoutAutoLength {
        binding.typeInput.setText(spool.baseMaterial, false)
        binding.finishInput.setText(spool.finishEnum.label, false)
        // Only re-apply defaults on an explicit material change; an existing spool's own numbers win.
        binding.typeInput.setOnItemClickListener { _, _, position, _ ->
            applyMaterialDefaults(materials[position])
            onMaterialChanged()
        }
        binding.finishInput.setOnItemClickListener { _, _, _, _ -> onMaterialChanged() }
        onMaterialChanged()
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

    private fun selectedBase(): String =
        binding.typeInput.text.toString().trim().ifBlank { materials.first() }

    private fun selectedFinish(): FilamentMaterial.Finish =
        FilamentMaterial.Finish.fromLabel(binding.finishInput.text.toString().trim())

    /**
     * Keeps the two notes under the material fields honest. Both are statements about the filament
     * and the printer, so they're shown as soon as the choice is made rather than sprung on the
     * user at write time.
     */
    private fun onMaterialChanged() {
        val base = selectedBase()
        val finish = selectedFinish()

        binding.fallbackNote.visibility =
            if (FilamentMaterial.needsFallback(base, finish)) {
                binding.fallbackNote.text =
                    "The ACE will show this as $base — Anycubic doesn't make " +
                    "${finish.label.lowercase()} filament, so there's no code for it on the tag. " +
                    "AceTag remembers."
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.abrasiveNote.visibility =
            if (finish.abrasive) View.VISIBLE else View.GONE
    }

    /**
     * Weight is the field that actually differs on a small spool, and length is the one that has
     * to follow it: the ACE counts down from what the tag claims, so 250 g written with a 1 kg
     * length reports four times the filament it has. Nobody is going to work out 82 m by hand, so
     * the field fills itself, visibly, and stays editable for anyone who knows better.
     */
    private fun onWeightChanged(text: String?) {
        if (fillingForm) return
        val weight = text?.trim()?.toIntOrNull() ?: return
        val length = SpoolTag.lengthForWeight(selectedBase(), weight)
        if (length > 0) withoutAutoLength { binding.lengthInput.setText(length.toString()) }
    }

    private inline fun withoutAutoLength(block: () -> Unit) {
        fillingForm = true
        try {
            block()
        } finally {
            fillingForm = false
        }
    }

    private fun applyMaterialDefaults(type: String) = withoutAutoLength {
        val d = SpoolTag.MATERIAL_DEFAULTS[type] ?: return@withoutAutoLength
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
        val material = selectedBase()
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
            // The finish only reaches the tag when Anycubic sells that combination; otherwise this
            // is the base material and the finish lives in the database. See [FilamentMaterial].
            type = FilamentMaterial.tagType(selectedBase(), selectedFinish()),
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
            // Only tags that exist can be out of date; a spool waiting for stickers just carries
            // its new details into whatever gets written next.
            val stale = spool.hasTags && (spool.tagsStale || changed)
            // The finish rides along outside the spec: switching wood to carbon fibre changes
            // nothing the stickers carry, so it saves without making them stale.
            val updated = spool.withSpec(spec)
                .copy(finish = selectedFinish().name, tagsStale = stale)
            repo.updateSpool(updated)

            if (changed && spool.hasTags && spool.source == SpoolSource.CUSTOM) {
                AlertDialog.Builder(this@CustomSpoolActivity)
                    .setTitle("Saved — but the stickers are out of date")
                    .setMessage(
                        "The two stickers on this spool still say what it used to. Rewrite them " +
                            "now, or do it later from the spool's page.",
                    )
                    .setPositiveButton("Rewrite now") { _, _ ->
                        existing = updated.copy(tagsStale = true)
                        writingExisting = true
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
            owners = repo.tagOwners()
            // A rewrite lands on the spool's own stickers: a move from itself, already agreed to
            // by having tapped "rewrite", so it never stops to ask.
            approvedMoves.clear()
            existing?.let { approvedMoves.add(it.id) }

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
        if (!approveOwner(uid, "1")) return
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
        if (!approveOwner(uid, "2")) return
        try {
            TagIo.write(tag, spec, groupId)
        } catch (e: Exception) {
            setStatus("Sticker 2 didn't take: ${e.message}\n\nHold it flat and steady, and try again.")
            return
        }
        step = Step.DONE
        save(spec, firstUid!!, uid)
    }

    /**
     * True if this sticker is free to use. A sticker worn by another spool isn't refused — moving
     * a pair from one spool to another is the point — but it is never taken silently.
     *
     * The confirmation can't be answered while the tag is still readable, so this asks and returns
     * false; the next tap of that sticker goes through. Approval is per spool, so the other half
     * of the same pair doesn't ask again.
     */
    private fun approveOwner(uid: String, sticker: String): Boolean {
        val owner = owners[uid] ?: return true
        if (owner.id in approvedMoves) return true
        confirmMove(owner, sticker)
        return false
    }

    private fun confirmMove(owner: SpoolEntity, sticker: String) {
        val name = SpoolDisplay.title(owner)
        AlertDialog.Builder(this)
            .setTitle("These tags are on $name")
            .setMessage(
                "Moving them to ${targetName()}.\n\n" +
                    "$name stays in your inventory — the printer just won't see it until it " +
                    "gets tags again.",
            )
            .setPositiveButton("Move them") { _, _ ->
                approvedMoves.add(owner.id)
                setStatus("Hold sticker $sticker against the back of the phone again.")
            }
            .setNegativeButton("Cancel") { _, _ ->
                setStatus("Nothing moved. Use a different sticker for sticker $sticker, or cancel.")
            }
            .show()
    }

    /** What the spool being written is called, whether it's in the inventory yet or not. */
    private fun targetName(): String {
        existing?.let { return SpoolDisplay.title(it) }
        val name = FilamentMaterial.displayName(selectedBase(), selectedFinish())
        val brand = specBeingWritten?.manufacturer ?: return name
        return "${SpoolDisplay.brand(brand)} ${SpoolDisplay.material(name)}".trim()
    }

    private fun save(spec: SpoolTag.Spec, uid1: String, uid2: String) {
        // Read before the write lands, while `owners` still describes the old arrangement.
        val displaced = listOf(uid1, uid2)
            .mapNotNull { owners[it] }
            .distinctBy { it.id }
            .filter { it.id != existing?.id }
        val rewrote = existing?.hasTags == true

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
                        finish = selectedFinish(),
                    ),
                )
            } else {
                // moveTagsTo writes the whole row, so the edited spec rides along with it.
                repo.moveTagsTo(
                    spool.withSpec(spec).copy(finish = selectedFinish().name),
                    uid1,
                    uid2,
                    groupId.toHexString(),
                )
            }
            toastLong(writeSummary(displaced, rewrote))
            finish()
        }
    }

    /**
     * Names both sides of the trade. A move costs one spool its tags, and saying so is what keeps
     * the inventory believable — otherwise a spool quietly stops being printable.
     */
    private fun writeSummary(displaced: List<SpoolEntity>, rewrote: Boolean): String = when {
        displaced.isNotEmpty() -> {
            val names = displaced.joinToString(" and ") { SpoolDisplay.title(it) }
            val verb = if (displaced.size == 1) "no longer has tags" else "no longer have tags"
            "${targetName()} is ready to print. $names $verb."
        }
        rewrote -> "Both tags rewritten."
        existing != null -> "Both tags written. ${targetName()} is ready to print."
        else -> "Both tags written. ${targetName()} added to your inventory."
    }

    /**
     * Adds the spool with no stickers on it. Normal for anything that rides on an adapter or a
     * reused spool: the filament is on the shelf and counted, and the tags go on when it's the
     * one going in the printer.
     */
    private fun saveWithoutTags() {
        val spec = readSpec() ?: return
        specBeingWritten = spec
        lifecycleScope.launch {
            SpoolRepository.get(this@CustomSpoolActivity).addSpool(
                spec.toSpool(source = SpoolSource.CUSTOM, finish = selectedFinish()),
            )
            toastLong("${targetName()} added. Put the tags on it when it goes in the printer.")
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

    /** For the outcomes worth reading — a move names two spools and needs longer than a blink. */
    private fun toastLong(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
