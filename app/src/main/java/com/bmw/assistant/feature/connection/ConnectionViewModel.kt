package com.bmw.assistant.feature.connection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bmw.assistant.BmwAssistantApp
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.core.ecu.EnetNetworkBinder
import com.bmw.assistant.core.ecu.EcuTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwAssistantApp).codingRepository
    private val diagRepo = (app as BmwAssistantApp).diagnosticsRepository
    private val networkBinder = EnetNetworkBinder(app)

    val state = ConnectionManager.state

    fun connectDemo() {
        viewModelScope.launch {
            ConnectionManager.connectDemo()
            if (!ConnectionManager.isLive) return@launch
            repo.seedDemoValues()
            withContext(Dispatchers.IO) {
                val modules = repo.getModules()
                val byId = modules.associateBy { it.id }
                val codings = byId.keys.flatMap { repo.getCodingsForModule(it) }
                val liveParams = diagRepo.allLiveParameters()
                val demoFaults = diagRepo.demoFaults()
                ConnectionManager.seedDemoTransport(byId, codings, liveParams, demoFaults)
                ConnectionManager.identifyVehicle(modules)
            }
        }
    }

    fun connectEnet(ip: String, port: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { networkBinder.bindPreferringLocalLan() }
            ConnectionManager.connectEnet(ip, port)
            if (!ConnectionManager.isLive) return@launch
            withContext(Dispatchers.IO) {
                ConnectionManager.identifyVehicle(repo.getModules())
            }
        }
    }

    fun connectBle(label: String, transport: EcuTransport) {
        viewModelScope.launch { ConnectionManager.connectBle(label, transport) }
    }

    fun disconnect() {
        networkBinder.unbind()
        ConnectionManager.disconnect()
    }

    override fun onCleared() {
        // Keep the connection alive across screens; only unbind if we disconnect.
    }
}
