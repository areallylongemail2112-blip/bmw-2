package com.bmw.assistant.feature.connection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.core.ecu.EcuTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwAssistantApp).codingRepository
    private val diagRepo = (app as BmwAssistantApp).diagnosticsRepository

    val state = ConnectionManager.state

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

    fun connectEnet(ip: String, port: Int) {
        viewModelScope.launch { ConnectionManager.connectEnet(ip, port) }
    }

    fun connectBle(label: String, transport: EcuTransport) {
        viewModelScope.launch { ConnectionManager.connectBle(label, transport) }
    }

    fun disconnect() = ConnectionManager.disconnect()
}
