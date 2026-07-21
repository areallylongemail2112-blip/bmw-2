package com.bmwf10.coding.ecu.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.bmwf10.coding.ecu.EcuException
import com.bmwf10.coding.ecu.EcuTransport
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE transport for OBD adapters that expose a Nordic UART Service (the common profile for
 * ELM327-class BLE dongles). It establishes a GATT link and can exchange ELM327 command
 * lines with the adapter.
 *
 * NOTE ON CODING: this transport reports [supportsCoding] = false. Consumer ELM327-class BLE
 * adapters expose only OBD-II / generic CAN request-response and cannot reliably perform the
 * multi-frame, security-gated UDS coding that BMW F-series modules require. The app therefore
 * uses BLE for connection/diagnostics and directs coding writes to ENET or demo mode. The GATT
 * plumbing here is real, so this class is the correct place to add a proprietary coding
 * protocol if you have one for your specific adapter.
 */
@SuppressLint("MissingPermission")
class BleObdTransport(
    private val context: Context,
    private val device: BluetoothDevice
) : EcuTransport {

    companion object {
        // Nordic UART Service (NUS) UUIDs — the de-facto standard for serial-over-BLE dongles.
        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // write
        val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notify
        // Client Characteristic Configuration Descriptor — must be written to actually
        // subscribe to notifications; setCharacteristicNotification alone is not enough.
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TIMEOUT_MS = 6000L
    }

    // These are written on the caller/IO thread and read in GATT binder-thread callbacks
    // (and vice-versa), so they need @Volatile to guarantee cross-thread visibility.
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var connected = false

    @Volatile private var connectLatch: CountDownLatch? = null
    private val lastLine = AtomicReference<String>("")
    @Volatile private var responseLatch: CountDownLatch? = null

    override val isConnected: Boolean get() = connected
    override val supportsCoding: Boolean get() = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                connectLatch?.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connected = false
                connectLatch?.countDown()
                return
            }
            val service = g.getService(NUS_SERVICE)
            rx = service?.getCharacteristic(NUS_RX)
            val tx = service?.getCharacteristic(NUS_TX)
            if (rx == null || tx == null) {
                connected = false
                connectLatch?.countDown()
                return
            }
            g.setCharacteristicNotification(tx, true)
            // Subscribe for real by writing the CCCD; without this the adapter never sends
            // notifications and every read blocks the full timeout. Completion (success or
            // failure) is signalled in onDescriptorWrite.
            val cccd = tx.getDescriptor(CCCD)
            if (cccd != null) {
                val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, enable)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = enable
                        g.writeDescriptor(cccd)
                    }
                }
            } else {
                // No CCCD present: the link is up but notifications can't be enabled.
                connected = true
                connectLatch?.countDown()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            connected = rx != null
            connectLatch?.countDown()
        }

        // API 33+ delivers the value directly to this overload.
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) = deliverLine(value)

        // Pre-33 devices call this overload; read the (deprecated) value field.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                deliverLine(ch.value ?: ByteArray(0))
            }
        }
    }

    private fun deliverLine(bytes: ByteArray) {
        lastLine.set(String(bytes))
        responseLatch?.countDown()
    }

    override fun connect() {
        val latch = CountDownLatch(1)
        connectLatch = latch
        // Prefer LE transport on dual-mode devices; auto can pick classic and fail NUS discovery.
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) || !connected) {
            disconnect()
            throw EcuException("BLE connect to ${device.address} failed or NUS not found")
        }
        // Basic ELM327 reset/echo-off handshake proves the link works end to end.
        sendLine("ATZ")
        sendLine("ATE0")
    }

    override fun disconnect() {
        connected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; rx = null
    }

    /** Sends one ELM327 command line and returns the adapter's reply (best-effort). */
    private fun sendLine(cmd: String): String {
        val ch = rx ?: throw EcuException("BLE not connected")
        val latch = CountDownLatch(1)
        responseLatch = latch
        val bytes = (cmd + "\r").toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(ch, bytes, ch.writeType)
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.value = bytes
                gatt?.writeCharacteristic(ch)
            }
        }
        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return lastLine.get()
    }

    override fun readCodingBlock(diagAddress: Int, did: Int): ByteArray {
        throw EcuException("Coding read over BLE is not supported by this adapter class. Use ENET.")
    }

    override fun writeCodingBlock(diagAddress: Int, did: Int, data: ByteArray) {
        throw EcuException("Coding write over BLE is not supported by this adapter class. Use ENET.")
    }
}
