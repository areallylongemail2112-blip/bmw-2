package com.bmwf10.coding.ecu

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bmwf10.coding.data.model.BackupSource
import com.bmwf10.coding.data.model.CodingBackup
import com.bmwf10.coding.data.model.CodingItem
import com.bmwf10.coding.data.model.Module
import com.bmwf10.coding.data.model.ValueType
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

    /** Active demo transport when connected in demo mode, otherwise null. */
    fun demoTransport(): DemoTransport? = transport as? DemoTransport

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
            runCatching { t.disconnect() }
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

    /** Backup source matching the active connection (demo vs. real hardware). */
    fun backupSource(): BackupSource =
        if (current.isDemo) BackupSource.DEMO else BackupSource.HARDWARE

    /**
     * Reads the raw bytes of one module coding block for a backup snapshot.
     * Must be called off the main thread.
     * @throws EcuException when not connected or the transport cannot read coding data.
     */
    fun readBlock(module: Module, dataIdentifier: Int): ByteArray {
        val engine = codingEngine() ?: throw EcuException("Not connected")
        return engine.readBlock(module, dataIdentifier)
    }

    /**
     * Writes [backup]'s saved bytes back to its module. Must be called off the main thread.
     *
     * Safety gate: a backup can only be restored onto the same kind of connection it was
     * captured from — demo snapshots never reach a real car, and hardware snapshots are not
     * pushed into the simulator.
     */
    fun restoreBackup(module: Module, backup: CodingBackup) {
        val engine = codingEngine() ?: throw EcuException("Not connected")
        if (backup.source != backupSource()) {
            throw EcuException(
                if (backup.source == BackupSource.DEMO)
                    "This backup was captured in demo mode and cannot be written to a real car."
                else
                    "This backup was captured from real hardware and cannot be restored in demo mode."
            )
        }
        engine.restoreBlock(module, backup.dataIdentifier, Hex.decode(backup.blockHex))
    }

    /**
     * Primes [DemoTransport] coding blocks from each item's demo/default value so a
     * subsequent read-modify-write matches the values shown in the UI.
     */
    fun seedDemoTransport(modules: Map<String, Module>, codings: List<CodingItem>) {
        val demo = demoTransport() ?: return
        for (coding in codings) {
            val module = modules[coding.moduleId] ?: continue
            val map = coding.ecuMap ?: continue
            if (map.byteOffset < 0 || map.bitMask == 0) continue
            val uiValue = coding.demoValue ?: coding.defaultValue
            val masked = try {
                encodeForSeed(coding, map.bitMask, map.encodedValues, map.scale, uiValue)
            } catch (_: Exception) {
                continue
            }
            demo.seedByte(module.diagAddress, map.dataIdentifier, map.byteOffset, masked, map.bitMask)
        }
    }

    private fun encodeForSeed(
        coding: CodingItem,
        bitMask: Int,
        encodedValues: Map<String, String>?,
        scale: Double,
        uiValue: String
    ): Int {
        val shift = Integer.numberOfTrailingZeros(bitMask and 0xFF)
        return when (coding.valueType) {
            ValueType.BOOLEAN, ValueType.ENUM -> {
                val encoded = encodedValues?.get(uiValue) ?: return 0
                Hex.parseByte(encoded) and bitMask
            }
            ValueType.INTEGER -> {
                val n = uiValue.toDoubleOrNull() ?: return 0
                val field = Math.round(n / scale).toInt()
                (field shl shift) and bitMask
            }
            ValueType.HEX -> {
                val field = Hex.parseByte(uiValue)
                (field shl shift) and bitMask
            }
        }
    }
}
