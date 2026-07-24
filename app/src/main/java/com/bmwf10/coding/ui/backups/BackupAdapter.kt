package com.bmwf10.coding.ui.backups

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bmwf10.coding.data.model.CodingBackup
import com.bmwf10.coding.databinding.ItemBackupBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupAdapter(
    private val onRestore: (CodingBackup) -> Unit,
    private val onDelete: (CodingBackup) -> Unit
) : ListAdapter<CodingBackup, BackupAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemBackupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBackupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val b = getItem(position)
        with(holder.binding) {
            backupModule.text = "${b.moduleName} · ${moduleFullHint(b)}"
            backupSource.text = b.source.name
            backupLabel.text = b.label
            backupMeta.text = "DID 0x%04X · %d bytes · %s".format(
                b.dataIdentifier, b.blockSize, DATE_FMT.format(Date(b.createdAt))
            )
            backupBytes.text = formatHex(b.blockHex)
            restoreButton.setOnClickListener { onRestore(b) }
            deleteButton.setOnClickListener { onDelete(b) }
        }
    }

    private fun moduleFullHint(b: CodingBackup): String = b.connectionLabel ?: b.moduleId.uppercase()

    private fun formatHex(hex: String): String =
        hex.uppercase().chunked(2).joinToString(" ").let {
            if (it.length > 71) it.take(71) + "…" else it
        }

    companion object {
        private val DATE_FMT = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<CodingBackup>() {
            override fun areItemsTheSame(a: CodingBackup, b: CodingBackup) = a.id == b.id
            override fun areContentsTheSame(a: CodingBackup, b: CodingBackup) = a == b
        }
    }
}
