package com.bmw.assistant.feature.coding

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.bmw.assistant.R
import com.bmw.assistant.databinding.ActivityCodingModulesBinding
import com.bmw.assistant.feature.backups.BackupsActivity
import com.bmw.assistant.ui.common.ConnectionBadge
import com.google.android.material.snackbar.Snackbar

/** Coding module picker — a card grid of every module that has coding features. */
class CodingModulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodingModulesBinding
    private val viewModel: CodingModulesViewModel by viewModels()

    private val importMaps = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        if (json.isNullOrBlank()) {
            Snackbar.make(binding.root, "Could not read that file.", Snackbar.LENGTH_LONG).show()
        } else {
            viewModel.importMapsJson(json)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodingModulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.coding_modules_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_backups -> {
                    startActivity(Intent(this, BackupsActivity::class.java))
                    true
                }
                R.id.action_import_maps -> {
                    importMaps.launch("application/json")
                    true
                }
                else -> false
            }
        }
        ConnectionBadge.bind(binding.connectionChip, this, this)

        val adapter = ModuleAdapter { card ->
            startActivity(
                Intent(this, CodingListActivity::class.java)
                    .putExtra(CodingListActivity.EXTRA_MODULE_ID, card.module.id)
            )
        }
        binding.moduleGrid.layoutManager = GridLayoutManager(this, 2)
        binding.moduleGrid.adapter = adapter

        viewModel.modules.observe(this) { adapter.submitList(it) }
        viewModel.message.observe(this) { ev ->
            ev.getIfNotHandled()?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }
}
