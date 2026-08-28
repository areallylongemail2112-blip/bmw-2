package com.bmw.assistant.feature.diagnostics

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bmw.assistant.databinding.ActivityDiagnosticsModuleBinding
import com.bmw.assistant.ui.common.ConnectionBadge
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/** Read fault codes and live data for a single module. */
class DiagnosticsModuleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsModuleBinding
    private val viewModel: DiagnosticsModuleViewModel by viewModels()
    private lateinit var moduleId: String

    private val faultAdapter = FaultAdapter()
    private val liveAdapter = LiveDataAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsModuleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        moduleId = intent.getStringExtra(EXTRA_MODULE_ID).orEmpty()
        ConnectionBadge.bind(binding.connectionChip, this, this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.faultList.layoutManager = LinearLayoutManager(this)
        binding.faultList.adapter = faultAdapter
        binding.faultList.isNestedScrollingEnabled = false

        binding.liveList.layoutManager = LinearLayoutManager(this)
        binding.liveList.adapter = liveAdapter
        binding.liveList.isNestedScrollingEnabled = false

        binding.scanButton.setOnClickListener { viewModel.scanFaults() }
        binding.clearButton.setOnClickListener { confirmClear() }
        binding.refreshLiveButton.setOnClickListener { viewModel.refreshLiveOnce() }
        binding.autoRefreshSwitch.setOnCheckedChangeListener { btn, checked ->
            if (btn.isPressed) viewModel.toggleAutoRefresh(checked)
        }

        observe()
        viewModel.load(moduleId)
    }

    private fun observe() {
        viewModel.module.observe(this) { m ->
            binding.toolbar.title = m?.name ?: "Diagnostics"
            binding.moduleSubtitle.text = m?.fullName ?: ""
        }
        viewModel.faults.observe(this) { rows ->
            faultAdapter.submitList(rows)
            binding.faultEmpty.visibility =
                if (rows.isEmpty() && viewModel.scanned.value == true) View.VISIBLE else View.GONE
        }
        viewModel.scanned.observe(this) { scanned ->
            binding.clearButton.visibility = if (scanned) View.VISIBLE else View.GONE
            if (scanned && viewModel.faults.value.isNullOrEmpty()) {
                binding.faultEmpty.visibility = View.VISIBLE
            }
        }
        viewModel.liveRows.observe(this) { rows ->
            liveAdapter.submitList(rows)
            binding.liveCard.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        }
        viewModel.autoRefreshing.observe(this) { on ->
            if (binding.autoRefreshSwitch.isChecked != on) binding.autoRefreshSwitch.isChecked = on
        }
        viewModel.busy.observe(this) { busy ->
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
            binding.scanButton.isEnabled = !busy
            binding.clearButton.isEnabled = !busy
        }
        viewModel.message.observe(this) { ev ->
            ev.getIfNotHandled()?.let { snack(it) }
        }
    }

    private fun confirmClear() {
        val name = viewModel.module.value?.name ?: "this module"
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear fault codes?")
            .setMessage(
                "This erases all stored fault codes in $name. Codes for faults that are still " +
                    "present will return on the next drive cycle."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ -> viewModel.clearFaults() }
            .show()
    }

    override fun onPause() {
        super.onPause()
        // Stop polling the car when the screen isn't visible.
        viewModel.toggleAutoRefresh(false)
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
    }
}
