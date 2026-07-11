package com.bmwf10.coding.ui.coding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bmwf10.coding.databinding.ItemCodingBinding

class CodingAdapter(
    private val onEdit: (CodingRowUi) -> Unit
) : ListAdapter<CodingRowUi, CodingAdapter.VH>(DIFF) {

    // Tracks which cards have their "What does this do?" section expanded.
    private val expanded = HashSet<String>()

    inner class VH(val binding: ItemCodingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCodingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val c = item.coding
        with(holder.binding) {
            codingName.text = c.name
            codingDescription.text = c.description
            currentValue.text = item.currentValueDisplay
            warningBadge.visibility = if (c.warning != null || c.irreversible) View.VISIBLE else View.GONE

            longDescription.text = c.longDescription
            val isOpen = expanded.contains(c.id)
            longDescription.visibility = if (isOpen) View.VISIBLE else View.GONE
            expandToggle.text = if (isOpen) "Hide details" else "What does this do?"
            expandToggle.setOnClickListener {
                if (expanded.contains(c.id)) expanded.remove(c.id) else expanded.add(c.id)
                notifyItemChanged(holder.bindingAdapterPosition)
            }

            editButton.setOnClickListener { onEdit(item) }
            root.setOnClickListener { onEdit(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CodingRowUi>() {
            override fun areItemsTheSame(a: CodingRowUi, b: CodingRowUi) =
                a.coding.id == b.coding.id
            override fun areContentsTheSame(a: CodingRowUi, b: CodingRowUi) = a == b
        }
    }
}
