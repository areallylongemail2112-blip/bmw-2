package com.bmw.assistant.feature.backups

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bmw.assistant.data.model.CodingBackup
import com.bmw.assistant.databinding.ActivityBackupsBinding
import com.bmw.assistant.ui.common.ConnectionBadge
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

/** Restore-point manager: view coding-block snapshots and write them back to a module. */
class BackupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupsBinding
    private val viewModel: BackupsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConnectionBadge.bind(binding.connectionChip, this, this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = BackupAdapter(
            onRestore = { confirmRestore(it) },
            onDelete = { viewModel.delete(it) }
        )
        binding.backupList.layoutManager = LinearLayoutManager(this)
        binding.backupList.adapter = adapter

        viewModel.backups.observe(this) { list ->
            adapter.submitList(list)
            binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
        viewModel.busy.observe(this) { busy ->
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
            binding.newBackupButton.isEnabled = !busy
        }
        viewModel.event.observe(this) { ev ->
            when (val a = ev.getIfNotHandled()) {
                is BackupAction.Restored ->
                    snack("Restored “${a.label}” to the module.")
                is BackupAction.Created -> snack(
                    if (a.count == 0) "Nothing new to back up — blocks are unchanged."
                    else "Saved ${a.count} backup${if (a.count == 1) "" else "s"}."
                )
                is BackupAction.Failed -> snack(a.message)
                BackupAction.NeedsConnection -> snack(
                    "Not connected. Open the connection screen (or use demo mode) first."
                )
                null -> {}
            }
        }

        binding.newBackupButton.setOnClickListener { viewModel.createBackupForAll() }
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun confirmRestore(backup: CodingBackup) {
        val bytes = backup.blockHex.uppercase().chunked(2).joinToString(" ")
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore this backup?")
            .setMessage(
                "Module: ${backup.moduleName}\n" +
                    "Block: DID 0x%04X\n\n".format(backup.dataIdentifier) +
                    "The following exact bytes will be written back to the module:\n\n$bytes"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> viewModel.restore(backup) }
            .show()
    }

    private fun snack(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }
}
