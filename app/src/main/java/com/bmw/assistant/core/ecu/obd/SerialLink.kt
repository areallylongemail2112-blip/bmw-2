package com.bmw.assistant.core.ecu.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import com.bmw.assistant.core.ecu.EcuException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A byte pipe to an ELM327-class OBD adapter. Three physical flavours exist and they all look
 * the same to [Elm327Transport]: write ASCII commands, read bytes back until the `>` prompt.
 */
interface SerialLink {
    val isOpen: Boolean
    val label: String
    fun open()
    fun close()
    fun write(bytes: ByteArray)
    /** Reads whatever is available, blocking up to [timeoutMs]. Returns 0 on timeout. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int
}

/** WiFi OBD adapters (ELM327-WiFi, vLinker/OBDLink WiFi): a plain TCP socket, usually 192.168.0.10:35000. */
class TcpSerialLink(private val host: String, private val port: Int = DEFAULT_PORT) : SerialLink {
    companion object {
        const val DEFAULT_PORT = 35000
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null

    override val isOpen: Boolean get() = socket?.isConnected == true && socket?.isClosed == false
    override val label: String get() = "WiFi OBD $host:$port"

    override fun open() {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()
        } catch (e: Exception) {
            runCatching { s.close() }
            throw EcuException("Could not reach the WiFi adapter at $host:$port. Is the phone on the adapter's WiFi network?", e)
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: throw EcuException("WiFi adapter not connected")
        out.write(bytes); out.flush()
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val s = socket ?: throw EcuException("WiFi adapter not connected")
        val inp = input ?: throw EcuException("WiFi adapter not connected")
        s.soTimeout = timeoutMs
        return try {
            inp.read(buffer).also { if (it < 0) throw EcuException("WiFi adapter closed the connection") }
        } catch (_: SocketTimeoutException) {
            0
        }
    }
}

/** Classic Bluetooth (SPP/RFCOMM) — the overwhelmingly common ELM327/STN dongle profile. */
@SuppressLint("MissingPermission")
class BluetoothSppSerialLink(private val device: BluetoothDevice) : SerialLink {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var open = false

    override val isOpen: Boolean get() = open && socket?.isConnected == true
    override val label: String = "Bluetooth " + (runCatching { device.name }.getOrNull() ?: device.address)

    override fun open() {
        var s: BluetoothSocket? = null
        try {
            s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
        } catch (e: Exception) {
            runCatching { s?.close() }
            // Fallback used by many cheap clones that advertise no SDP record.
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                s = m.invoke(device, 1) as BluetoothSocket
                s.connect()
            } catch (e2: Exception) {
                runCatching { s?.close() }
                e2.addSuppressed(e)
                throw EcuException(
                    "Bluetooth connect to $label failed. " +
                        "Pair the adapter in Android Bluetooth settings first and make sure ignition is on.", e2
                )
            }
        }
        socket = s
        input = s!!.inputStream
        output = s.outputStream
        open = true
    }

    override fun close() {
        open = false
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: throw EcuException("Bluetooth adapter not connected")
        out.write(bytes); out.flush()
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val inp = input ?: throw EcuException("Bluetooth adapter not connected")
        // BluetoothSocket streams have no read timeout; poll available() instead.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val avail = inp.available()
            if (avail > 0) return inp.read(buffer, 0, minOf(avail, buffer.size))
            if (!isOpen) throw EcuException("Bluetooth adapter disconnected")
            Thread.sleep(5)
        }
        return 0
    }
}

/**
 * BLE adapters exposing a Nordic UART Service (NUS) or the very similar "FFE0/FFE1" serial
 * profile used by many ELM327-BLE clones. Notifications are queued into a buffer that [read]
 * drains, so the transport sees an ordinary byte stream.
 */
@SuppressLint("MissingPermission")
class BleSerialLink(private val context: Context, private val device: BluetoothDevice) : SerialLink {

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // write
        val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notify
        val FFE0_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val FFE1_CHAR: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val FFF0_SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val FFF1_CHAR: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb") // notify
        val FFF2_CHAR: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb") // write
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TIMEOUT_MS = 8000L
        private const val WRITE_TIMEOUT_MS = 1500L
        private const val REQUESTED_MTU = 185
        private const val ATT_HEADER = 3
        private const val DEFAULT_CHUNK = 20
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var connected = false
    @Volatile private var connectLatch: CountDownLatch? = null
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var writeOk = true
    /** Payload bytes per GATT write: negotiated MTU minus the 3-byte ATT header, else 20. */
    @Volatile private var chunkSize = DEFAULT_CHUNK
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private var leftover: ByteArray = ByteArray(0)
    private var leftoverPos = 0

    override val isOpen: Boolean get() = connected
    override val label: String = "BLE " + (runCatching { device.name }.getOrNull() ?: device.address)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                // Some clones never answer an MTU request; fall through to discovery directly.
                if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
            } else {
                connected = false
                connectLatch?.countDown()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > ATT_HEADER + DEFAULT_CHUNK) chunkSize = mtu - ATT_HEADER
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connected = false; connectLatch?.countDown(); return
            }
            var notify: BluetoothGattCharacteristic? = null
            g.getService(NUS_SERVICE)?.let { svc ->
                writeChar = svc.getCharacteristic(NUS_RX); notify = svc.getCharacteristic(NUS_TX)
            }
            if (writeChar == null) g.getService(FFE0_SERVICE)?.getCharacteristic(FFE1_CHAR)?.let {
                writeChar = it; notify = it
            }
            if (writeChar == null) g.getService(FFF0_SERVICE)?.let { svc ->
                writeChar = svc.getCharacteristic(FFF2_CHAR); notify = svc.getCharacteristic(FFF1_CHAR)
            }
            val tx = notify
            if (tx == null || writeChar == null) {
                connected = false; connectLatch?.countDown(); return
            }
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD)
            if (cccd == null) {
                // Some clones have no CCCD but still notify.
                connected = true; connectLatch?.countDown(); return
            }
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") cccd.value = enable
                @Suppress("DEPRECATION") g.writeDescriptor(cccd)
            }
            if (!ok) { connected = false; connectLatch?.countDown() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD) {
                connected = status == BluetoothGatt.GATT_SUCCESS
                connectLatch?.countDown()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeOk = status == BluetoothGatt.GATT_SUCCESS
            writeLatch?.countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            // On Android 13+ the framework delivers the 3-arg overload; handling both here would
            // duplicate every byte from the adapter and corrupt the ELM stream.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION") val v = ch.value ?: return
            incoming.offer(v.copyOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.offer(value.copyOf())
        }
    }

    override fun open() {
        val latch = CountDownLatch(1)
        connectLatch = latch
        incoming.clear(); leftover = ByteArray(0); leftoverPos = 0
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) || !connected) {
            close()
            throw EcuException(
                "BLE connect to $label failed. Make sure the adapter is powered, not connected to " +
                    "another phone, and exposes a serial service (NUS/FFE0/FFF0)."
            )
        }
    }

    override fun close() {
        connected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; writeChar = null
        incoming.clear()
    }

    override fun write(bytes: ByteArray) {
        val ch = writeChar ?: throw EcuException("BLE not connected")
        val g = gatt ?: throw EcuException("BLE not connected")
        val chunk = chunkSize
        var i = 0
        while (i < bytes.size) {
            val part = bytes.copyOfRange(i, minOf(i + chunk, bytes.size))
            val latch = CountDownLatch(1)
            writeLatch = latch
            writeOk = true
            val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val issued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, part, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") ch.writeType = type
                @Suppress("DEPRECATION") ch.value = part
                @Suppress("DEPRECATION") g.writeCharacteristic(ch)
            }
            if (!issued) throw EcuException("BLE write failed (adapter busy or disconnected)")
            if (!latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw EcuException("BLE write timed out")
            if (!writeOk) throw EcuException("BLE write rejected by adapter")
            i += chunk
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        var n = 0
        // Drain a partially consumed notification first.
        n += copyLeftover(buffer, n)
        if (n == 0) {
            val first = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            leftover = first; leftoverPos = 0
            n += copyLeftover(buffer, n)
        }
        while (n < buffer.size) {
            val next = incoming.poll() ?: break
            leftover = next; leftoverPos = 0
            n += copyLeftover(buffer, n)
        }
        return n
    }

    private fun copyLeftover(buffer: ByteArray, offset: Int): Int {
        val avail = leftover.size - leftoverPos
        if (avail <= 0) return 0
        val count = minOf(avail, buffer.size - offset)
        System.arraycopy(leftover, leftoverPos, buffer, offset, count)
        leftoverPos += count
        return count
    }
}
