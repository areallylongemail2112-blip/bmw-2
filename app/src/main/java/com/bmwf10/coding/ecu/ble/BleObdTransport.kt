package com.bmwf10.coding.ecu.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
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
        private const val TIMEOUT_MS = 6000L
    }

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var connected = false

    private var connectLatch: CountDownLatch? = null
    private val lastLine = AtomicReference<String>("")
    private var responseLatch: CountDownLatch? = null

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
            val service = g.getService(NUS_SERVICE)
            rx = service?.getCharacteristic(NUS_RX)
            val tx = service?.getCharacteristic(NUS_TX)
            if (tx != null) {
                g.setCharacteristicNotification(tx, true)
            }
            connected = rx != null && tx != null
            connectLatch?.countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            lastLine.set(String(ch.value ?: ByteArray(0)))
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
        val latch = CountDownLatch(1)
        responseLatch = latch
        ch.value = (cmd + "\r").toByteArray()
        gatt?.writeCharacteristic(ch)
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
