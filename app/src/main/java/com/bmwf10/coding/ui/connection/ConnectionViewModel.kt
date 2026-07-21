package com.bmwf10.coding.ui.connection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bmwf10.coding.BmwCodingApp
import com.bmwf10.coding.ecu.ConnectionManager
import com.bmwf10.coding.ecu.ConnectionStatus
import com.bmwf10.coding.ecu.EcuTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwCodingApp).repository

    val state = ConnectionManager.state

    fun connectDemo() {
        viewModelScope.launch {
            ConnectionManager.connectDemo()
            // Only seed when the demo link actually came up.
            if (ConnectionManager.current.status != ConnectionStatus.CONNECTED) return@launch
            repo.seedDemoValues()
            withContext(Dispatchers.IO) {
                val modules = repo.getModules().associateBy { it.id }
                val codings = modules.keys.flatMap { repo.getCodingsForModule(it) }
                ConnectionManager.seedDemoTransport(modules, codings)
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
