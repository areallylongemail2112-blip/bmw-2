package com.bmw.assistant.feature.connection

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.core.ecu.EnetDiscovery
import com.bmw.assistant.core.ecu.EnetGateway
import com.bmw.assistant.core.ecu.EnetProtocol
import com.bmw.assistant.ui.common.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the connection screen. All transport work runs on Dispatchers.IO inside
 * [ConnectionManager]; this ViewModel only sequences it and exposes discovery results.
 */
class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwAssistantApp).codingRepository
    private val diagRepo = (app as BmwAssistantApp).diagnosticsRepository

    val state = ConnectionManager.state

    private val _discovering = MutableLiveData(false)
    val discovering: LiveData<Boolean> = _discovering

    private val _discovered = MutableLiveData<Event<List<EnetGateway>>>()
    val discovered: LiveData<Event<List<EnetGateway>>> = _discovered

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

    /** Broadcasts HSFZ/DoIP identification requests and reports every gateway that answers. */
    fun discoverGateways() {
        if (_discovering.value == true) return
        _discovering.value = true
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { EnetDiscovery.discover(getApplication()) }
            _discovering.value = false
            _discovered.value = Event(found)
        }
    }

    fun connectEnet(ip: String, protocol: EnetProtocol) {
        connectHardware {
            when (protocol) {
                EnetProtocol.HSFZ -> ConnectionManager.connectEnetHsfz(getApplication(), ip)
                EnetProtocol.DOIP -> ConnectionManager.connectEnetDoip(getApplication(), ip)
            }
        }
    }

    fun connectObdBluetooth(device: BluetoothDevice, label: String) {
        connectHardware { ConnectionManager.connectObdBluetooth(device, label) }
    }

    fun connectObdBle(device: BluetoothDevice, label: String) {
        connectHardware { ConnectionManager.connectObdBle(getApplication(), device, label) }
    }

    fun connectObdWifi(ip: String, port: Int) {
        connectHardware { ConnectionManager.connectObdWifi(getApplication(), ip, port) }
    }

    /**
     * Connects to real hardware and drops the locally stored values first.
     *
     * The value store is the app's mirror of what the car holds. After a demo session it is full
     * of simulated values; showing those next to a real car would tell the driver a feature is on
     * when it is not. Cleared here, each screen reads the actual bytes from the car instead.
     */
    private fun connectHardware(connect: suspend () -> Unit) {
        viewModelScope.launch {
            repo.clearValues()
            connect()
        }
    }

    fun disconnect() {
        viewModelScope.launch { ConnectionManager.disconnect() }
    }
}
