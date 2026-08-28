package com.bmw.assistant.feature.coding

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bmw.assistant.databinding.ItemModuleBinding
import com.bmw.assistant.ui.common.Icons

class ModuleAdapter(
    private val onClick: (ModuleCardUi) -> Unit
) : ListAdapter<ModuleCardUi, ModuleAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemModuleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            moduleIcon.setImageResource(Icons.drawableFor(item.module.iconName))
            moduleName.text = item.module.name
            moduleFullName.text = item.module.fullName
            moduleCount.text = "${item.codingCount} codings"
            root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ModuleCardUi>() {
            override fun areItemsTheSame(a: ModuleCardUi, b: ModuleCardUi) =
                a.module.id == b.module.id
            override fun areContentsTheSame(a: ModuleCardUi, b: ModuleCardUi) = a == b
        }
    }
}
