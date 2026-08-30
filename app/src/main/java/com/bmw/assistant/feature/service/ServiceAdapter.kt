package com.bmw.assistant.feature.service

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bmw.assistant.R
import com.bmw.assistant.data.model.ServiceFunction
import com.bmw.assistant.databinding.ItemServiceBinding

class ServiceAdapter(
    private val onRun: (ServiceFunction) -> Unit
) : ListAdapter<ServiceFunction, ServiceAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemServiceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemServiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = getItem(position)
        with(holder.binding) {
            serviceName.text = s.name
            serviceDescription.text = s.description
            val status = if (s.verified) {
                root.context.getString(R.string.services_verified)
            } else {
                root.context.getString(R.string.services_illustrative)
            }
            serviceMeta.text = "${s.moduleId.uppercase()} · $status"
            runButton.contentDescription = root.context.getString(R.string.services_run) + " " + s.name
            runButton.setOnClickListener { onRun(s) }
            root.setOnClickListener { onRun(s) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ServiceFunction>() {
            override fun areItemsTheSame(a: ServiceFunction, b: ServiceFunction) = a.id == b.id
            override fun areContentsTheSame(a: ServiceFunction, b: ServiceFunction) = a == b
        }
    }
}
