package com.bmw.assistant.feature.diagnostics

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bmw.assistant.databinding.ItemFaultBinding

class FaultAdapter : ListAdapter<FaultRowUi, FaultAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemFaultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFaultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            faultSae.text = item.saeCode
            faultHex.text = "0x${item.hexCode}"
            faultStatus.text = item.status
            if (item.description.isNullOrBlank()) {
                faultDescription.visibility = View.GONE
            } else {
                faultDescription.visibility = View.VISIBLE
                faultDescription.text = item.description
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FaultRowUi>() {
            override fun areItemsTheSame(a: FaultRowUi, b: FaultRowUi) = a.hexCode == b.hexCode
            override fun areContentsTheSame(a: FaultRowUi, b: FaultRowUi) = a == b
        }
    }
}
