package com.bmw.assistant.core.ecu

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bmw.assistant.core.coding.CodingEngine
import com.bmw.assistant.core.diagnostics.DiagnosticsEngine
import com.bmw.assistant.core.ecu.obd.BleSerialLink
import com.bmw.assistant.core.ecu.obd.BluetoothSppSerialLink
import com.bmw.assistant.core.ecu.obd.Elm327Transport
import com.bmw.assistant.core.ecu.obd.TcpSerialLink
import com.bmw.assistant.core.ecu.uds.Doip
import com.bmw.assistant.core.ecu.uds.Hsfz
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.bmw.assistant.data.model.BackupSource
import com.bmw.assistant.data.model.CodingBackup
import com.bmw.assistant.data.model.CodingItem
import com.bmw.assistant.data.model.DemoFault
import com.bmw.assistant.data.model.LiveParameter
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.data.model.ValueType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How the phone is linked to the car.
 *  - DEMO       offline simulator
 *  - ENET_HSFZ  ENET cable / ENET-WiFi adapter, HSFZ framing on TCP 6801 (F-series incl. F10)
 *  - ENET_DOIP  ENET, DoIP framing on TCP 13400 (G-series / late F-series)
 *  - OBD_BT     Bluetooth Classic ELM327/STN dongle (D-CAN via the OBD port)
 *  - OBD_BLE    Bluetooth Low Energy ELM327 dongle
 *  - OBD_WIFI   WiFi ELM327 dongle (TCP, usually 192.168.0.10:35000)
 */
enum class ConnectionType { DEMO, ENET_HSFZ, ENET_DOIP, OBD_BT, OBD_BLE, OBD_WIFI }
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class ConnectionState(
    val type: ConnectionType? = null,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val label: String? = null,
    val message: String? = null,
    val supportsCoding: Boolean = false,
    val supportsDiagnostics: Boolean = false,
    val vehicleInfo: String? = null
) {
    val isConnected get() = status == ConnectionStatus.CONNECTED
    val isDemo get() = type == ConnectionType.DEMO
    val isHardware get() = isConnected && !isDemo
}

/**
 * Application-scoped holder of the live connection and its transport. Screens observe [state]
 * to reflect connection status in the top bar and to gate coding/diagnostics actions.
 *
 * Singleton (rather than a per-screen ViewModel) because the connection must survive
 * navigation between the Home hub, coding screens, and diagnostics screens.
 */
object ConnectionManager {

    private val _state = MutableLiveData(ConnectionState())
    val state: LiveData<ConnectionState> = _state

    @Volatile private var transport: EcuTransport? = null

    /**
     * Connection type of the live transport. Kept separately from [current] because
     * `LiveData.postValue` is asynchronous — callers that run immediately after [connectDemo]
     * would otherwise still see DISCONNECTED and treat a demo session as hardware.
     */
    @Volatile private var liveType: ConnectionType? = null

    val current: ConnectionState get() = _state.value ?: ConnectionState()

    /**
     * True the instant a transport is connected — set synchronously in [connectWith], unlike
     * [current] whose status arrives via `LiveData.postValue` and isn't visible on the same
     * thread that awaited the connect. Callers that act immediately after connecting (e.g. demo
     * seeding) must gate on this, not on `current.status`.
     */
    val isLive: Boolean get() = transport?.isConnected == true

    /** A coding engine bound to the active transport, or null if not connected. */
    fun codingEngine(): CodingEngine? {
        val t = transport ?: return null
        if (!t.isConnected) return null
        return CodingEngine(t, liveType == ConnectionType.DEMO)
    }

    /** A UDS client bound to the active transport for identification/expert reads. */
    fun udsClient(): UdsClient? {
        val t = transport ?: return null
        if (!t.isConnected) return null
        return UdsClient(t)
    }

    /** Human-readable description of the active link, or null. */
    fun transportDescription(): String? = transport?.takeIf { it.isConnected }?.description

    /** A diagnostics engine bound to the active transport, or null if not connected/capable. */
    fun diagnosticsEngine(): DiagnosticsEngine? {
        val t = transport ?: return null
        if (!t.isConnected || !t.supportsDiagnostics) return null
        return DiagnosticsEngine(t)
    }

    /** Active demo transport when connected in demo mode, otherwise null. */
    fun demoTransport(): DemoTransport? = transport as? DemoTransport

    suspend fun connectDemo() = connectWith(
        ConnectionType.DEMO, "Demo Mode", DemoTransport()
    )

    /** ENET over HSFZ (TCP 6801) — the right choice for a 2010–2016 F10/F11. */
    suspend fun connectEnetHsfz(ip: String, port: Int = Hsfz.PORT_TCP) =
        connectWith(ConnectionType.ENET_HSFZ, "ENET $ip", EnetHsfzTransport(ip, port))

    /** ENET over DoIP (TCP 13400) — G-series and late F-series gateways. */
    suspend fun connectEnetDoip(ip: String, port: Int = Doip.PORT) =
        connectWith(ConnectionType.ENET_DOIP, "ENET/DoIP $ip", EnetDoipTransport(ip, port))

    /** Bluetooth Classic (SPP) ELM327/STN adapter. */
    suspend fun connectObdBluetooth(device: BluetoothDevice, label: String) =
        connectWith(ConnectionType.OBD_BT, label, Elm327Transport(BluetoothSppSerialLink(device)))

    /** BLE ELM327 adapter. */
    suspend fun connectObdBle(context: Context, device: BluetoothDevice, label: String) =
        connectWith(ConnectionType.OBD_BLE, label, Elm327Transport(BleSerialLink(context.applicationContext, device)))

    /** WiFi ELM327 adapter. */
    suspend fun connectObdWifi(ip: String, port: Int = 35000) =
        connectWith(ConnectionType.OBD_WIFI, "WiFi OBD $ip", Elm327Transport(TcpSerialLink(ip, port)))

    private suspend fun connectWith(type: ConnectionType, label: String, t: EcuTransport) {
        disconnect()
        _state.postValue(ConnectionState(type, ConnectionStatus.CONNECTING, label))
        try {
            withContext(Dispatchers.IO) { t.connect() }
            transport = t
            liveType = type
            val vehicle = withContext(Dispatchers.IO) { identifyVehicle(t, type) }
            _state.postValue(
                ConnectionState(
                    type, ConnectionStatus.CONNECTED, label,
                    supportsCoding = t.supportsCoding,
                    supportsDiagnostics = t.supportsDiagnostics,
                    vehicleInfo = vehicle
                )
            )
        } catch (e: Exception) {
            runCatching { t.disconnect() }
            transport = null
            liveType = null
            _state.postValue(
                ConnectionState(type, ConnectionStatus.ERROR, label, e.message ?: "Connection failed")
            )
        }
    }

    fun disconnect() {
        val t = transport
        transport = null
        liveType = null
        runCatching { t?.disconnect() }
        _state.postValue(ConnectionState())
    }

    /**
     * Best-effort VIN / link description. Identification failure must never fail the connection.
     */
    private fun identifyVehicle(t: EcuTransport, type: ConnectionType): String? {
        if (type == ConnectionType.DEMO) {
            return "Demo 2012 F10 · VIN ${DemoTransport.DEMO_VIN}"
        }
        val uds = UdsClient(t)
        for (addr in intArrayOf(0x40, 0x10, 0x60)) {
            val data = runCatching { uds.readDataByIdentifier(addr, VIN_DID) }.getOrNull() ?: continue
            val vin = String(data, Charsets.ISO_8859_1).filter { it.isLetterOrDigit() }.take(17)
            if (vin.length == 17) {
                val link = t.description
                return "VIN $vin · $link"
            }
        }
        return t.description
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
     * Primes [DemoTransport] state so a subsequent read matches what the UI shows: coding
     * blocks from each item's demo/default value, live blocks from each parameter's demoRaw,
     * and each module's fault memory from the demo fault set.
     */
    fun seedDemoTransport(
        modules: Map<String, Module>,
        codings: List<CodingItem>,
        liveParams: List<LiveParameter>,
        demoFaults: List<DemoFault>
    ) {
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
            demo.seedCodingByte(module.diagAddress, map.dataIdentifier, map.byteOffset, masked, map.bitMask)
        }

        for (param in liveParams) {
            val module = modules[param.moduleId] ?: continue
            val raw = try {
                Hex.decode(param.demoRaw)
            } catch (_: Exception) {
                continue
            }
            demo.seedLiveBlock(module.diagAddress, param.dataIdentifier, raw)
        }

        for (fault in demoFaults) {
            val module = modules[fault.moduleId] ?: continue
            val dtc = try {
                Hex.decode(fault.dtc)
            } catch (_: Exception) {
                continue
            }
            if (dtc.size != 3) continue
            demo.seedFault(module.diagAddress, dtc, fault.status)
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

    companion object {
        /** UDS DID for the 17-character VIN (ISO 14229 / BMW F-series). */
        const val VIN_DID = 0xF190
    }
}
