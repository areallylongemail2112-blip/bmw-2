package com.bmw.assistant.core.ecu

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bmw.assistant.core.coding.CodingEngine
import com.bmw.assistant.core.diagnostics.DiagnosticsEngine
import com.bmw.assistant.core.ecu.obd.BleSerialLink
import com.bmw.assistant.core.ecu.obd.BluetoothSppSerialLink
import com.bmw.assistant.core.ecu.obd.Elm327Transport
import com.bmw.assistant.core.ecu.obd.TcpSerialLink
import com.bmw.assistant.core.ecu.net.LinkNetwork
import com.bmw.assistant.core.ecu.uds.Doip
import com.bmw.assistant.core.ecu.uds.Hsfz
import com.bmw.assistant.core.ecu.uds.Uds
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.bmw.assistant.data.model.BackupSource
import com.bmw.assistant.data.model.CodingBackup
import com.bmw.assistant.data.model.CodingItem
import com.bmw.assistant.data.model.DemoFault
import com.bmw.assistant.data.model.LiveParameter
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.data.model.ValueType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** VIN read from the car after connecting, or null when it could not be identified. */
    val vin: String? = null,
    /** One line describing the car and link, for the connection bar. */
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

    private const val VIN_DID = 0xF190

    private val _state = MutableLiveData(ConnectionState())
    val state: LiveData<ConnectionState> = _state

    @Volatile private var transport: EcuTransport? = null

    /**
     * Connection work runs on an app-lifetime scope, not the calling ViewModel's: a connect
     * that is half-way through a TCP/GATT handshake when the user leaves the screen must still
     * finish (and be recorded as [transport]) or be torn down — never orphan an open socket
     * with its keep-alive thread. Callers `join` the work so they can still await the outcome.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises connect/disconnect so two taps cannot race on [transport]. */
    private val linkMutex = Mutex()

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
        // Demo-ness comes from the transport instance, never from LiveData that may lag behind.
        return CodingEngine(t, t is DemoTransport)
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

    suspend fun connectDemo() = connectWith(ConnectionType.DEMO, "Demo Mode") { DemoTransport() }

    /**
     * ENET over HSFZ (TCP 6801) — the right choice for a 2010–2016 F10/F11.
     *
     * [context] is used to pin the sockets to the ENET link: an ENET cable or ENET-WiFi adapter
     * offers no internet, so Android would otherwise route the connection out the mobile
     * interface and the car would never see it.
     */
    suspend fun connectEnetHsfz(context: Context, ip: String, port: Int = Hsfz.PORT_TCP) =
        connectWith(
            ConnectionType.ENET_HSFZ, "ENET $ip", context
        ) { EnetHsfzTransport(ip, port) }

    /** ENET over DoIP (TCP 13400) — G-series and late F-series gateways. */
    suspend fun connectEnetDoip(context: Context, ip: String, port: Int = Doip.PORT) =
        connectWith(
            ConnectionType.ENET_DOIP, "ENET/DoIP $ip", context
        ) { EnetDoipTransport(ip, port) }

    /** Bluetooth Classic (SPP) ELM327/STN adapter. */
    suspend fun connectObdBluetooth(device: BluetoothDevice, label: String) =
        connectWith(ConnectionType.OBD_BT, label) { Elm327Transport(BluetoothSppSerialLink(device)) }

    /** BLE ELM327 adapter. */
    suspend fun connectObdBle(context: Context, device: BluetoothDevice, label: String) =
        connectWith(ConnectionType.OBD_BLE, label) {
            Elm327Transport(BleSerialLink(context.applicationContext, device))
        }

    /** WiFi ELM327 adapter — also on an internet-less network, so it is pinned the same way. */
    suspend fun connectObdWifi(context: Context, ip: String, port: Int = TcpSerialLink.DEFAULT_PORT) =
        connectWith(
            ConnectionType.OBD_WIFI, "WiFi OBD $ip", context
        ) { Elm327Transport(TcpSerialLink(ip, port)) }

    /**
     * @param localNetworkContext non-null for links that live on an internet-less local network
     *   (ENET, WiFi OBD), which must be pinned before the socket is opened.
     * @param create builds the transport *inside* the connect job, so a cancelled caller never
     *   leaves a half-built link behind.
     */
    private suspend fun connectWith(
        type: ConnectionType,
        label: String,
        localNetworkContext: Context? = null,
        create: () -> EcuTransport
    ) {
        // join() is cancellable for the caller, but the launched job itself is not cancelled
        // when the caller goes away — see [scope].
        scope.launch {
            linkMutex.withLock {
                closeCurrent()
                _state.postValue(ConnectionState(type, ConnectionStatus.CONNECTING, label))
                var built: EcuTransport? = null
                try {
                    if (localNetworkContext != null) LinkNetwork.acquire(localNetworkContext)
                    val t = create()
                    built = t
                    t.connect()
                    transport = t
                    val vin = identifyVehicle(t)
                    _state.postValue(
                        ConnectionState(
                            type, ConnectionStatus.CONNECTED, label,
                            supportsCoding = t.supportsCoding, supportsDiagnostics = t.supportsDiagnostics,
                            vin = vin, vehicleInfo = describeVehicle(t, type, vin)
                        )
                    )
                } catch (e: Exception) {
                    runCatching { built?.disconnect() }
                    transport = null
                    LinkNetwork.release()
                    _state.postValue(
                        ConnectionState(type, ConnectionStatus.ERROR, label, userMessage(e))
                    )
                }
            }
        }.join()
    }

    /**
     * Reads the VIN so the app can tell which car it is attached to. Coding bytes are only
     * meaningful for the module they came from, so every backup is stamped with this and a
     * restore onto a different car is refused.
     *
     * Best effort, asking each module that commonly holds it in turn. A car that answers none of
     * them still connects — it just loses the cross-car guard, and the UI says so. Identification
     * must never be the reason a connection fails.
     */
    private fun identifyVehicle(t: EcuTransport): String? {
        val client = UdsClient(t)
        for (address in VIN_SOURCES) {
            val vin = runCatching {
                String(client.readDataByIdentifier(address, Uds.DID_VIN), Charsets.ISO_8859_1)
            }.getOrNull()
                ?.filter { it.isLetterOrDigit() }
                ?.takeIf { it.length == VIN_LENGTH }
            if (vin != null) return vin
        }
        return null
    }

    /** One line for the connection bar: which car, over which link. */
    private fun describeVehicle(t: EcuTransport, type: ConnectionType, vin: String?): String {
        val link = t.description
        return when {
            type == ConnectionType.DEMO -> "Demo 2012 F10 · VIN ${vin ?: DemoTransport.DEMO_VIN}"
            vin != null -> "VIN $vin · $link"
            else -> "$link · car did not report a VIN"
        }
    }

    /** Turns a transport failure into one sentence a driver can act on. */
    private fun userMessage(e: Exception): String = when (e) {
        is EcuException -> e.message ?: "Connection failed"
        is SecurityException -> "Permission denied. Grant the Bluetooth permission and try again."
        else -> e.message ?: "Connection failed"
    }

    /**
     * Closes the active link. Socket/adapter teardown does blocking I/O (an ELM327 protocol
     * close, a TCP FIN), so it runs on Dispatchers.IO — never on the main thread.
     */
    suspend fun disconnect() {
        scope.launch {
            linkMutex.withLock {
                closeCurrent()
                _state.postValue(ConnectionState())
            }
        }.join()
    }

    /** Must be called with [linkMutex] held, on an IO thread. */
    private fun closeCurrent() {
        val t = transport
        transport = null
        if (t != null) runCatching { t.disconnect() }
        LinkNetwork.release()
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

    /** VIN of the connected car, or null in demo mode / when the car did not answer. */
    fun vin(): String? = current.vin

    /** Backup source matching the active connection (demo vs. real hardware). */
    fun backupSource(): BackupSource =
        if (transport is DemoTransport) BackupSource.DEMO else BackupSource.HARDWARE

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
        val connectedVin = current.vin
        if (backup.vin != null && connectedVin != null && backup.vin != connectedVin) {
            throw EcuException(
                "This backup was captured from VIN ${backup.vin}, but ${connectedVin} is connected. " +
                    "Coding bytes are specific to one car — refusing to write them to a different one."
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

    private const val CAS_ADDRESS = 0x40
    private const val KOMBI_ADDRESS = 0x60
    private const val DME_ADDRESS = 0x12
    private const val VIN_LENGTH = 17

    /**
     * Modules asked for the VIN, in order. On an F-series the car access system (CAS) is the
     * authoritative holder; the gateway, instrument cluster and engine controller are fallbacks
     * for cars that do not answer it there.
     */
    private val VIN_SOURCES = intArrayOf(
        CAS_ADDRESS, FramedTcpTransport.ZGW_ADDRESS, KOMBI_ADDRESS, DME_ADDRESS
    )

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
