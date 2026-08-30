package com.bmw.assistant.feature.diagnostics

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bmw.assistant.databinding.ItemLiveParamBinding

class LiveDataAdapter : ListAdapter<LiveRowUi, LiveDataAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemLiveParamBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLiveParamBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        with(holder.binding) {
            liveName.text = item.name
            liveValue.text = item.value
            liveValue.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    root.context,
                    if (item.isError) com.bmw.assistant.R.color.status_error
                    else com.bmw.assistant.R.color.primary
                )
            )
            sparkline.setSamples(item.history)
            sparkline.visibility = if (item.history.size >= 2) View.VISIBLE else View.GONE
            if (item.description.isBlank()) {
                liveDescription.visibility = View.GONE
            } else {
                liveDescription.visibility = View.VISIBLE
                liveDescription.text = item.description
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LiveRowUi>() {
            override fun areItemsTheSame(a: LiveRowUi, b: LiveRowUi) = a.id == b.id
            override fun areContentsTheSame(a: LiveRowUi, b: LiveRowUi) = a == b
        }
    }
}
