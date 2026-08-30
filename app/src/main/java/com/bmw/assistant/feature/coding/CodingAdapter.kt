package com.bmw.assistant.feature.coding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bmw.assistant.R
import com.bmw.assistant.data.model.ValueSource
import com.bmw.assistant.databinding.ItemCodingBinding

class CodingAdapter(
    private val onEdit: (CodingRowUi) -> Unit
) : ListAdapter<CodingRowUi, CodingAdapter.VH>(DIFF) {

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
        val ctx = holder.binding.root.context
        with(holder.binding) {
            codingName.text = c.name
            codingDescription.text = c.description
            currentValue.text = item.currentValueDisplay
            valueSource.text = when (item.source) {
                ValueSource.FROM_CAR -> ctx.getString(R.string.source_from_car)
                ValueSource.LOCAL_CACHE -> ctx.getString(R.string.source_local_cache)
                ValueSource.DEFAULT -> ctx.getString(R.string.source_default)
            }
            warningBadge.visibility = if (c.warning != null || c.irreversible) View.VISIBLE else View.GONE
            warningBadge.contentDescription = ctx.getString(R.string.warning)

            longDescription.text = c.longDescription
            val isOpen = expanded.contains(c.id)
            longDescription.visibility = if (isOpen) View.VISIBLE else View.GONE
            expandToggle.text = if (isOpen) ctx.getString(R.string.hide_details)
            else ctx.getString(R.string.what_does_this_do)
            expandToggle.setOnClickListener {
                if (expanded.contains(c.id)) expanded.remove(c.id) else expanded.add(c.id)
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            }

            editButton.setOnClickListener { onEdit(item) }
            root.contentDescription = "${c.name}, ${item.currentValueDisplay}, ${valueSource.text}"
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
