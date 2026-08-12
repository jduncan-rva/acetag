package com.jamieduncan.acetag

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jamieduncan.acetag.data.SpoolRepository
import com.jamieduncan.acetag.data.SpoolSource
import com.jamieduncan.acetag.data.toSpool
import com.jamieduncan.acetag.databinding.ActivityAddSpoolBinding
import kotlinx.coroutines.launch

/**
 * Workflow 1: a spool that came with its own tag. Everything on this screen was read off the tag,
 * so there is nothing to fill in — just confirm it's the spool in your hand and add it.
 *
 * If you already own spools of the same product and colour, that's said plainly here. It isn't a
 * duplicate to resolve; it's another spool, and the inventory counts spools.
 */
class AddSpoolActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_UID = "uid"
        private const val EXTRA_GROUP_ID = "group_id"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_MANUFACTURER = "manufacturer"
        private const val EXTRA_COLOR = "color"
        private const val EXTRA_NOZZLE_MIN = "nozzle_min"
        private const val EXTRA_NOZZLE_MAX = "nozzle_max"
        private const val EXTRA_BED_MIN = "bed_min"
        private const val EXTRA_BED_MAX = "bed_max"
        private const val EXTRA_SPEED_MIN = "speed_min"
        private const val EXTRA_SPEED_MAX = "speed_max"
        private const val EXTRA_DIAMETER = "diameter"
        private const val EXTRA_LENGTH = "length"
        private const val EXTRA_WEIGHT = "weight"

        fun intent(context: Context, result: TagIo.ReadResult): Intent {
            val spec = requireNotNull(result.spec) { "AddSpoolActivity needs a decoded tag" }
            return Intent(context, AddSpoolActivity::class.java)
                .putExtra(EXTRA_UID, result.uid)
                .putExtra(EXTRA_GROUP_ID, result.groupIdHex)
                .putExtra(EXTRA_TYPE, spec.type)
                .putExtra(EXTRA_MANUFACTURER, spec.manufacturer)
                .putExtra(EXTRA_COLOR, spec.color)
                .putExtra(EXTRA_NOZZLE_MIN, spec.nozzleMin)
                .putExtra(EXTRA_NOZZLE_MAX, spec.nozzleMax)
                .putExtra(EXTRA_BED_MIN, spec.bedMin)
                .putExtra(EXTRA_BED_MAX, spec.bedMax)
                .putExtra(EXTRA_SPEED_MIN, spec.speedMin)
                .putExtra(EXTRA_SPEED_MAX, spec.speedMax)
                .putExtra(EXTRA_DIAMETER, spec.diameterMm)
                .putExtra(EXTRA_LENGTH, spec.lengthM)
                .putExtra(EXTRA_WEIGHT, spec.weightG)
        }
    }

    private lateinit var binding: ActivityAddSpoolBinding
    private lateinit var uid: String
    private lateinit var spec: SpoolTag.Spec
    private var groupIdHex: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddSpoolBinding.inflate(layoutInflater)
        setContentView(binding.root)

        uid = intent.getStringExtra(EXTRA_UID) ?: run { finish(); return }
        groupIdHex = intent.getStringExtra(EXTRA_GROUP_ID)
        spec = SpoolTag.Spec(
            type = intent.getStringExtra(EXTRA_TYPE).orEmpty(),
            manufacturer = intent.getStringExtra(EXTRA_MANUFACTURER).orEmpty(),
            color = intent.getStringExtra(EXTRA_COLOR).orEmpty(),
            nozzleMin = intent.getIntExtra(EXTRA_NOZZLE_MIN, 0),
            nozzleMax = intent.getIntExtra(EXTRA_NOZZLE_MAX, 0),
            bedMin = intent.getIntExtra(EXTRA_BED_MIN, 0),
            bedMax = intent.getIntExtra(EXTRA_BED_MAX, 0),
            speedMin = intent.getIntExtra(EXTRA_SPEED_MIN, 0),
            speedMax = intent.getIntExtra(EXTRA_SPEED_MAX, 0),
            diameterMm = intent.getDoubleExtra(EXTRA_DIAMETER, 1.75),
            lengthM = intent.getIntExtra(EXTRA_LENGTH, 0),
            weightG = intent.getIntExtra(EXTRA_WEIGHT, 0),
        )

        render()
        binding.addButton.setOnClickListener { add() }
        binding.cancelButton.setOnClickListener { finish() }
    }

    private fun render() {
        binding.titleText.text = SpoolDisplay.title(spec)
        binding.subtitleText.text = SpoolDisplay.summary(spec)
        binding.specsText.text = SpoolDisplay.details(spec)
        binding.tagText.text = "Tag $uid"
        binding.swatch.setSwatchColor(spec.color)

        lifecycleScope.launch {
            val onHand = SpoolRepository.get(this@AddSpoolActivity).countOfSameColor(spec)
            if (onHand > 0) {
                binding.alreadyHaveText.text =
                    "You already have ${SpoolDisplay.spoolCount(onHand)} of this exact filament. " +
                        "Adding this one makes it ${onHand + 1}."
                binding.alreadyHaveCard.visibility = View.VISIBLE
            }
        }
    }

    private fun add() {
        binding.addButton.isEnabled = false
        lifecycleScope.launch {
            val repo = SpoolRepository.get(this@AddSpoolActivity)

            // Belt and braces: ScanActivity already checked, but the user could have added this
            // spool on another screen in between. Adding it twice would overcount the inventory.
            if (repo.tagIsKnown(uid)) {
                Toast.makeText(this@AddSpoolActivity, "That spool is already in your inventory.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            repo.addSpool(
                spec.toSpool(source = SpoolSource.ANYCUBIC, tagUid = uid, groupId = groupIdHex),
            )
            Toast.makeText(
                this@AddSpoolActivity,
                "Added ${SpoolDisplay.title(spec)} to your inventory.",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }
}
