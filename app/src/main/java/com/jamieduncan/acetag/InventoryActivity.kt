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
import com.jamieduncan.acetag.data.AppDatabase
import com.jamieduncan.acetag.data.toExportJson
import com.jamieduncan.acetag.databinding.ActivityInventoryBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class InventoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventoryBinding
    private lateinit var adapter: SpoolAdapter
    private var observeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SpoolAdapter { spool ->
            startActivity(
                Intent(this, SpoolDetailActivity::class.java)
                    .putExtra(SpoolDetailActivity.EXTRA_SPOOL_ID, spool.id),
            )
        }
        binding.spoolList.layoutManager = LinearLayoutManager(this)
        binding.spoolList.adapter = adapter

        binding.newSpoolButton.setOnClickListener {
            startActivity(Intent(this, WriteSpoolActivity::class.java))
        }
        binding.readTagButton.setOnClickListener {
            startActivity(Intent(this, ReadTagActivity::class.java))
        }
        binding.usedUpToggle.setOnCheckedChangeListener { _, _ -> observeList() }
        binding.exportButton.setOnClickListener { exportJson() }

        observeList()
    }

    private fun exportJson() {
        lifecycleScope.launch {
            val spools = AppDatabase.get(this@InventoryActivity).spoolDao().getAll()
            if (spools.isEmpty()) {
                Toast.makeText(this@InventoryActivity, "No spools to export yet.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val json = spools.toExportJson(System.currentTimeMillis()).toString(2)
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Spool inventory JSON", json))
            Toast.makeText(
                this@InventoryActivity,
                "Copied ${spools.size} spool(s) as JSON to clipboard.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun observeList() {
        observeJob?.cancel()
        val dao = AppDatabase.get(this).spoolDao()
        val flow = if (binding.usedUpToggle.isChecked) dao.observeUsedUp() else dao.observeActive()
        observeJob = lifecycleScope.launch {
            flow.collect { spools ->
                adapter.submitList(spools)
                binding.emptyText.visibility = if (spools.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
