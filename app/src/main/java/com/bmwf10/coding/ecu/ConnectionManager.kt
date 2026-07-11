package com.bmwf10.coding.ecu

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bmwf10.coding.data.model.CodingItem
import com.bmwf10.coding.data.model.Module
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ConnectionType { DEMO, WIFI_ENET, BLE }
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ConnectionState(
    val type: ConnectionType? = null,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val label: String? = null,
    val message: String? = null
) {
    val isConnected get() = status == ConnectionStatus.CONNECTED
    val isDemo get() = type == ConnectionType.DEMO
    val supportsCoding get() = type == ConnectionType.DEMO || type == ConnectionType.WIFI_ENET
}

/**
 * Application-scoped holder of the live connection and its transport. Screens observe
 * [state] to reflect connection status in the top bar and to gate coding actions.
 *
 * Singleton (rather than a per-screen ViewModel) because the connection must survive
 * navigation between Home / Coding List / Edit / Connection screens.
 */
object ConnectionManager {

    private val _state = MutableLiveData(ConnectionState())
    val state: LiveData<ConnectionState> = _state

    @Volatile private var transport: EcuTransport? = null

    val current: ConnectionState get() = _state.value ?: ConnectionState()

    /** A coding engine bound to the active transport, or null if not connected. */
    fun codingEngine(): CodingEngine? {
        val t = transport ?: return null
        if (!t.isConnected) return null
        return CodingEngine(t, current.isDemo)
    }

    suspend fun connectDemo() = connectWith(
        ConnectionType.DEMO, "Demo Mode", DemoTransport()
    )

    suspend fun connectEnet(ip: String, port: Int = com.bmwf10.coding.ecu.uds.Doip.PORT) =
        connectWith(ConnectionType.WIFI_ENET, "ENET $ip", EnetDoipTransport(ip, port))

    suspend fun connectBle(device: String, t: EcuTransport) =
        connectWith(ConnectionType.BLE, device, t)

    private suspend fun connectWith(type: ConnectionType, label: String, t: EcuTransport) {
        disconnect()
        _state.postValue(ConnectionState(type, ConnectionStatus.CONNECTING, label))
        try {
            withContext(Dispatchers.IO) { t.connect() }
            transport = t
            _state.postValue(ConnectionState(type, ConnectionStatus.CONNECTED, label))
        } catch (e: Exception) {
            transport = null
            _state.postValue(
                ConnectionState(type, ConnectionStatus.ERROR, label, e.message ?: "Connection failed")
            )
        }
    }

    fun disconnect() {
        val t = transport
        transport = null
        runCatching { t?.disconnect() }
        _state.postValue(ConnectionState())
    }

    /**
     * Reads the live value of [coding] from the car (or demo). Returns null if the map
     * cannot be read. Must be called off the main thread.
     */
    fun readValue(module: Module, coding: CodingItem): String? =
        codingEngine()?.readCoding(module, coding)
}
