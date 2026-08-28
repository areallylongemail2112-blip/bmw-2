package com.bmw.assistant.feature.diagnostics

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bmw.assistant.databinding.ItemModuleBinding
import com.bmw.assistant.ui.common.Icons

class DiagModuleAdapter(
    private val onClick: (DiagModuleUi) -> Unit
) : ListAdapter<DiagModuleUi, DiagModuleAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemModuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            moduleIcon.setImageResource(Icons.drawableFor(item.module.iconName))
            moduleName.text = item.module.name
            moduleFullName.text = item.module.fullName
            moduleCount.text = if (item.hasLiveData) "Faults · Live data" else "Fault codes"
            root.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DiagModuleUi>() {
            override fun areItemsTheSame(a: DiagModuleUi, b: DiagModuleUi) =
                a.module.id == b.module.id
            override fun areContentsTheSame(a: DiagModuleUi, b: DiagModuleUi) = a == b
        }
    }
}
