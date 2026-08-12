package com.jamieduncan.acetag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jamieduncan.acetag.data.SpoolRepository
import com.jamieduncan.acetag.data.buildExportJson
import com.jamieduncan.acetag.data.groupBySpec
import com.jamieduncan.acetag.databinding.ActivityInventoryBinding
import kotlinx.coroutines.launch

/**
 * The whole inventory, one row per physical spool. There is no second list: a spool that's been
 * used up is deleted, and lives on only as an event in the history.
 */
class InventoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventoryBinding
    private lateinit var adapter: SpoolAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SpoolAdapter { group ->
            // A group of one is just a spool — don't make you tap through a list of one to reach it.
            startActivity(
                if (group.count == 1) {
                    Intent(this, SpoolDetailActivity::class.java)
                        .putExtra(SpoolDetailActivity.EXTRA_SPOOL_ID, group.newest.id)
                } else {
                    SpoolGroupActivity.intent(this, group.manufacturer, group.type, group.color)
                },
            )
        }
        binding.spoolList.layoutManager = LinearLayoutManager(this)
        binding.spoolList.adapter = adapter

        binding.scanButton.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        binding.customSpoolButton.setOnClickListener {
            startActivity(Intent(this, CustomSpoolActivity::class.java))
        }
        binding.exportButton.setOnClickListener { exportJson() }

        lifecycleScope.launch {
            SpoolRepository.get(this@InventoryActivity).observeAll().collect { spools ->
                val groups = spools.groupBySpec()
                adapter.submitList(groups)
                // Counted in spools, not lines: three identical spools on one line is still three.
                binding.countText.text = SpoolDisplay.spoolCount(spools.size)
                binding.emptyText.visibility = if (spools.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun exportJson() {
        lifecycleScope.launch {
            val repo = SpoolRepository.get(this@InventoryActivity)
            val spools = repo.getAll()
            val events = repo.getAllEvents()
            if (spools.isEmpty() && events.isEmpty()) {
                toast("Nothing to export yet.")
                return@launch
            }
            val json = buildExportJson(spools, events, System.currentTimeMillis()).toString(2)
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("AceTag inventory", json))
            toast("Copied ${SpoolDisplay.spoolCount(spools.size)} and ${events.size} history entries to the clipboard.")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
