package com.jamieduncan.acetag

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jamieduncan.acetag.data.AppDatabase
import com.jamieduncan.acetag.data.SpoolEntity
import com.jamieduncan.acetag.databinding.ActivitySpoolDetailBinding
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

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
        if (spoolId < 0) {
            finish()
            return
        }

        binding.reprintButton.setOnClickListener {
            startActivity(
                Intent(this, WriteSpoolActivity::class.java)
                    .putExtra(WriteSpoolActivity.EXTRA_REPRINT_SPOOL_ID, spoolId),
            )
        }
        binding.deleteButton.setOnClickListener { confirmDelete() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val spool = AppDatabase.get(this@SpoolDetailActivity).spoolDao().getById(spoolId)
            if (spool == null) {
                finish()
                return@launch
            }
            render(spool)
        }
    }

    private fun render(spool: SpoolEntity) {
        binding.detailTitle.text = "${spool.manufacturer} ${spool.type}"
        try {
            val bg = (binding.detailSwatch.background as GradientDrawable).mutate() as GradientDrawable
            bg.setColor(Color.parseColor(spool.color))
            binding.detailSwatch.background = bg
        } catch (_: Exception) {
        }

        val df = DateFormat.getDateInstance()
        binding.detailStatus.text = if (spool.usedUpAt != null) {
            "Used up ${df.format(Date(spool.usedUpAt))}"
        } else {
            "Logged ${df.format(Date(spool.createdAt))}"
        }

        val speedLine = if (spool.speedMax > 0) {
            "\nPrint speed: ${spool.speedMin}-${spool.speedMax} mm/s"
        } else {
            ""
        }
        binding.detailSpecs.text = "Color: ${spool.color}\n" +
            "Nozzle: ${spool.nozzleMin}-${spool.nozzleMax}°C\n" +
            "Bed: ${spool.bedMin}-${spool.bedMax}°C$speedLine\n" +
            "Diameter: ${spool.diameterMm}mm\n" +
            "Length: ${spool.lengthM}m\n" +
            "Weight: ${spool.weightG}g"

        binding.detailTags.text = "Tag A: ${spool.tagUidA ?: "not written"}\n" +
            "Tag B: ${spool.tagUidB ?: "not written"}"

        binding.usedUpButton.text = if (spool.usedUpAt != null) "Restore to Active" else "Mark Used Up"
        binding.usedUpButton.setOnClickListener {
            lifecycleScope.launch {
                val dao = AppDatabase.get(this@SpoolDetailActivity).spoolDao()
                val newUsedUpAt = if (spool.usedUpAt != null) null else System.currentTimeMillis()
                dao.update(spool.copy(usedUpAt = newUsedUpAt))
                load()
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this spool?")
            .setMessage("This removes it from your inventory history. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    AppDatabase.get(this@SpoolDetailActivity).spoolDao().delete(spoolId)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
