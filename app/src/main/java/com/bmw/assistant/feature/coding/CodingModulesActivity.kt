package com.bmw.assistant.feature.coding

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.bmw.assistant.R
import com.bmw.assistant.databinding.ActivityCodingModulesBinding
import com.bmw.assistant.feature.backups.BackupsActivity
import com.bmw.assistant.ui.common.ConnectionBadge

/** Coding module picker — a card grid of every module that has coding features. */
class CodingModulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodingModulesBinding
    private val viewModel: CodingModulesViewModel by viewModels()

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
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }
}
