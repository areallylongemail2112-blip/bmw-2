package com.bmw.assistant.feature.diagnostics

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.bmw.assistant.databinding.ActivityDiagnosticsModulesBinding
import com.bmw.assistant.ui.common.ConnectionBadge

/** Diagnostics module picker — every module, tap one to read its faults / live data. */
class DiagnosticsModulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsModulesBinding
    private val viewModel: DiagnosticsModulesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsModulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        ConnectionBadge.bind(binding.connectionChip, this, this)

        val adapter = DiagModuleAdapter { row ->
            startActivity(
                Intent(this, DiagnosticsModuleActivity::class.java)
                    .putExtra(DiagnosticsModuleActivity.EXTRA_MODULE_ID, row.module.id)
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
