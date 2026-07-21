package com.bmwf10.coding.ui.connection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bmwf10.coding.BmwCodingApp
import com.bmwf10.coding.ecu.ConnectionManager
import com.bmwf10.coding.ecu.EcuTransport
import kotlinx.coroutines.launch

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as BmwCodingApp).repository

    val state = ConnectionManager.state

    fun connectDemo() {
        viewModelScope.launch {
            ConnectionManager.connectDemo()
            // Only seed after a successful connect — connectWith swallows failures into ERROR.
            if (ConnectionManager.current.isConnected) {
                repo.seedDemoValues()
            }
        }
    }

    fun connectEnet(ip: String, port: Int) {
        viewModelScope.launch { ConnectionManager.connectEnet(ip, port) }
    }

    fun connectBle(label: String, transport: EcuTransport) {
        viewModelScope.launch { ConnectionManager.connectBle(label, transport) }
    }

    fun disconnect() {
        viewModelScope.launch { ConnectionManager.disconnect() }
    }
}
