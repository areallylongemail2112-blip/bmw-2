package com.bmwf10.coding.ecu.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

data class BleDevice(val name: String, val address: String)

/**
 * Thin wrapper over the Android BLE scanner used by the connection screen to list nearby
 * OBD adapters. The caller is responsible for holding BLUETOOTH_SCAN / (pre-12) location
 * permission before starting a scan.
 */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    val isAvailable: Boolean get() = adapter?.isEnabled == true

    private var callback: ScanCallback? = null

    fun start(onFound: (BleDevice) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev: BluetoothDevice = result.device
                // Many OBD dongles advertise without a local name; still show them by address.
                val name = dev.name
                    ?: result.scanRecord?.deviceName
                    ?: "OBD ${dev.address}"
                if (seen.add(dev.address)) onFound(BleDevice(name, dev.address))
            }
        }
        callback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)
    }

    fun stop() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        callback?.let { scanner.stopScan(it) }
        callback = null
    }

    fun deviceFor(address: String): BluetoothDevice? = adapter?.getRemoteDevice(address)
}
