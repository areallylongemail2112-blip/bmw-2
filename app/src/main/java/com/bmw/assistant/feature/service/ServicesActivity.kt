package com.bmw.assistant.feature.service

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bmw.assistant.R
import com.bmw.assistant.databinding.ActivityServicesBinding
import com.bmw.assistant.ui.common.ConnectionBadge
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class ServicesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServicesBinding
    private val viewModel: ServicesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConnectionBadge.bind(binding.connectionChip, this, this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = ServiceAdapter { confirmRun(it) }
        binding.serviceList.layoutManager = LinearLayoutManager(this)
        binding.serviceList.adapter = adapter

        viewModel.services.observe(this) { adapter.submitList(it) }
        viewModel.busy.observe(this) { busy ->
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        }
        viewModel.event.observe(this) { ev ->
            when (val a = ev.getIfNotHandled()) {
                is ServiceAction.Ran -> snack(getString(R.string.services_ran, a.name))
                is ServiceAction.Failed -> snack(a.message)
                ServiceAction.NeedsConnection -> snack(getString(R.string.error_not_connected))
                null -> {}
            }
        }

        viewModel.load(intent.getStringExtra(EXTRA_MODULE_ID))
    }

    private fun confirmRun(service: com.bmw.assistant.data.model.ServiceFunction) {
        val warning = service.warning?.let { "\n\n$it" } ?: ""
        MaterialAlertDialogBuilder(this)
            .setTitle(service.name)
            .setMessage(service.longDescription.ifBlank { service.description } + warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.services_run) { _, _ -> viewModel.run(service) }
            .show()
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
    }
}
