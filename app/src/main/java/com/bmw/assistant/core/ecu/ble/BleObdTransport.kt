package com.bmw.assistant.core.ecu.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.bmw.assistant.core.ecu.EcuException
import com.bmw.assistant.core.ecu.EcuTransport
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * BLE transport for OBD adapters that expose a Nordic UART Service (the common profile for
 * ELM327-class BLE dongles). It establishes a GATT link and can exchange ELM327 command
 * lines with the adapter.
 *
 * NOTE ON CAPABILITIES: this transport reports [supportsCoding] = false and
 * [supportsDiagnostics] = false. Consumer ELM327-class BLE adapters expose only OBD-II /
 * generic CAN request-response and cannot reliably perform the multi-frame, security-gated UDS
 * that BMW F-series modules require for coding, nor the module-addressed UDS 0x19/0x22 this app
 * uses for diagnostics. The app therefore uses BLE for connection/handshake and directs coding
 * and diagnostics to ENET or demo mode. The GATT plumbing here is real, so this class is the
 * correct place to add an ELM327 UDS bridge or a proprietary protocol for a specific adapter.
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
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TIMEOUT_MS = 6000L
    }

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var connected = false

    private var connectLatch: CountDownLatch? = null
    private var cccdLatch: CountDownLatch? = null
    private val lastLine = AtomicReference<String>("")
    private var responseLatch: CountDownLatch? = null

    override val isConnected: Boolean get() = connected
    override val supportsCoding: Boolean get() = false
    override val supportsDiagnostics: Boolean get() = false

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
            val service = g.getService(NUS_SERVICE)
            rx = service?.getCharacteristic(NUS_RX)
            val tx = service?.getCharacteristic(NUS_TX)
            if (tx == null || rx == null) {
                connected = false
                connectLatch?.countDown()
                return
            }
            g.setCharacteristicNotification(tx, true)
            // Enable notifications via the Client Characteristic Configuration Descriptor.
            // Without this write, many NUS adapters never deliver TX notifies.
            val cccd = tx.getDescriptor(CCCD)
            if (cccd == null) {
                connected = false
                connectLatch?.countDown()
                return
            }
            val latch = CountDownLatch(1)
            cccdLatch = latch
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val wrote = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!wrote) {
                connected = false
                connectLatch?.countDown()
            }
            // onDescriptorWrite finishes the connect handshake.
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD) {
                connected = status == BluetoothGatt.GATT_SUCCESS && rx != null
                cccdLatch?.countDown()
                connectLatch?.countDown()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            lastLine.set(String(ch.value ?: ByteArray(0)))
            responseLatch?.countDown()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            lastLine.set(String(value))
            responseLatch?.countDown()
        }
    }

    override fun connect() {
        val latch = CountDownLatch(1)
        connectLatch = latch
        gatt = device.connectGatt(context, false, callback)
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
        val g = gatt ?: throw EcuException("BLE not connected")
        val latch = CountDownLatch(1)
        responseLatch = latch
        val payload = (cmd + "\r").toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return lastLine.get()
    }

    override fun transceive(diagAddress: Int, request: ByteArray): ByteArray {
        throw EcuException(
            "This BLE adapter class exposes only ELM327 serial access; module-addressed UDS " +
                "(coding and diagnostics) needs ENET. Use ENET or demo mode."
        )
    }
}
