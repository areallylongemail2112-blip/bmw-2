package com.bmwf10.coding.ui.connection

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bmwf10.coding.databinding.ItemBleDeviceBinding
import com.bmwf10.coding.ecu.ble.BleDevice

class BleDeviceAdapter(
    private val onClick: (BleDevice) -> Unit
) : ListAdapter<BleDevice, BleDeviceAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemBleDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBleDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = getItem(position)
        holder.binding.deviceName.text = d.name
        holder.binding.deviceAddress.text = d.address
        holder.binding.root.setOnClickListener { onClick(d) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BleDevice>() {
            override fun areItemsTheSame(a: BleDevice, b: BleDevice) = a.address == b.address
            override fun areContentsTheSame(a: BleDevice, b: BleDevice) = a == b
        }
    }
}
