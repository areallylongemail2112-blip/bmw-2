package com.bmw.assistant.feature.connection

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.core.ecu.EnetDiscovery
import com.bmw.assistant.core.ecu.EnetGateway
import com.bmw.assistant.ui.common.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwAssistantApp).codingRepository
    private val diagRepo = (app as BmwAssistantApp).diagnosticsRepository

    val state = ConnectionManager.state

    private val _gateways = MutableLiveData<Event<List<EnetGateway>>>()
    val gateways: LiveData<Event<List<EnetGateway>>> = _gateways

    private val _notice = MutableLiveData<Event<String>>()
    val notice: LiveData<Event<String>> = _notice

    fun connectDemo() {
        viewModelScope.launch {
            ConnectionManager.connectDemo()
            // Only seed when the demo link actually came up. Gate on the synchronously-set
            // transport, not the posted status — LiveData.postValue hasn't been applied on this
            // thread yet, so current.status would still read CONNECTING here.
            if (!ConnectionManager.isLive) return@launch
            repo.seedDemoValues()
            withContext(Dispatchers.IO) {
                val modules = repo.getModules().associateBy { it.id }
                val codings = modules.keys.flatMap { repo.getCodingsForModule(it) }
                val liveParams = diagRepo.allLiveParameters()
                val demoFaults = diagRepo.demoFaults()
                ConnectionManager.seedDemoTransport(modules, codings, liveParams, demoFaults)
            }
        }
    }

    fun discoverEnet() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { EnetDiscovery.discover() }.getOrElse { emptyList() }
            }
            _gateways.value = Event(found)
            if (found.isEmpty()) {
                _notice.value = Event(
                    "No gateway found. Plug in the ENET cable (or join the adapter Wi-Fi), " +
                        "switch the ignition on, and try again — or type the IP below."
                )
            }
        }
    }

    fun connectEnet(ip: String, hsfz: Boolean) {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) {
            _notice.value = Event("Enter the car/gateway IP address.")
            return
        }
        viewModelScope.launch {
            if (hsfz) ConnectionManager.connectEnetHsfz(trimmed)
            else ConnectionManager.connectEnetDoip(trimmed)
        }
    }

    fun connectObdBluetooth(device: BluetoothDevice, label: String) {
        viewModelScope.launch { ConnectionManager.connectObdBluetooth(device, label) }
    }

    fun connectObdBle(context: Context, device: BluetoothDevice, label: String) {
        viewModelScope.launch { ConnectionManager.connectObdBle(context, device, label) }
    }

    fun connectObdWifi(ip: String, port: Int) {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) {
            _notice.value = Event("Enter the WiFi adapter IP address.")
            return
        }
        viewModelScope.launch { ConnectionManager.connectObdWifi(trimmed, port) }
    }

    fun disconnect() = ConnectionManager.disconnect()
}
