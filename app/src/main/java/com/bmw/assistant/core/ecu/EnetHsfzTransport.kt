package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Hsfz
import com.bmw.assistant.core.ecu.uds.Uds
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * ENET transport for F-series cars (incl. the 2010–2016 F10/F11): UDS over BMW's HSFZ framing
 * on TCP port 6801. Works with an ENET cable (phone → USB-C Ethernet adapter → OBD) and with
 * ENET-WiFi adapters, which bridge the same TCP stream over WiFi.
 *
 * Connection sequence:
 *   1. TCP connect to the gateway (ZGW), default 192.168.0.10:6801 for a direct cable; use
 *      [EnetDiscovery] to find the address when the car hands out something else.
 *   2. No routing activation exists in HSFZ — the first diagnostic frame is the handshake. We
 *      send a TesterPresent to the gateway (0x10) to prove the link is alive.
 *
 * Per request: UDS bytes are wrapped in a diagnostic frame addressed to the module; the
 * gateway echoes an ACK (control 0x0002), then forwards the module's answer (0x0001).
 * "Response pending" (0x7F xx 0x78) is absorbed with an extended wait, and gateway alive checks
 * (0x0012) are answered inline so the ZGW does not drop the connection mid-session.
 */
class EnetHsfzTransport(
    private val host: String,
    private val port: Int = Hsfz.PORT_TCP,
    private val connectTimeoutMs: Int = 4000,
    private val readTimeoutMs: Int = 5000,
    /** Max wait for a final answer after the module reported "response pending". */
    private val pendingTimeoutMs: Int = 30_000
) : EcuTransport {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val lock = Any()
    private val keepAlive = TesterPresentKeepAlive { addr, req -> rawTransceive(addr, req) }

    override val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false
    override val supportsCoding: Boolean get() = true
    override val supportsDiagnostics: Boolean get() = true
    override val description: String get() = "ENET (HSFZ) $host:$port"

    override fun connect() {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()

            // Handshake: the gateway itself must answer a TesterPresent.
            val resp = rawTransceive(ZGW_ADDRESS, Uds.testerPresent())
            if (!Uds.isPositive(resp, Uds.SID_TESTER_PRESENT) && Uds.negativeResponseCode(resp) == null) {
                throw EcuException("Gateway did not answer TesterPresent (" + Hex.encode(resp) + ")")
            }
            keepAlive.start()
        } catch (e: Exception) {
            runCatching { s.close() }
            socket = null; input = null; output = null
            if (e is EcuException) throw e
            throw EcuException(
                "ENET connect to $host:$port failed: ${e.message ?: e.javaClass.simpleName}. " +
                    "Check the cable/WiFi adapter, that ignition is on, and the gateway IP.", e
            )
        }
    }

    override fun disconnect() {
        keepAlive.stop()
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    override fun transceive(diagAddress: Int, request: ByteArray): ByteArray {
        keepAlive.touch(diagAddress)
        return rawTransceive(diagAddress, request)
    }

    private fun rawTransceive(diagAddress: Int, request: ByteArray): ByteArray = synchronized(lock) {
        transceiveLocked(diagAddress, request)
    }

    private fun transceiveLocked(diagAddress: Int, request: ByteArray): ByteArray {
        if (!isConnected) throw EcuException("ENET transport not connected")
        val out = output ?: throw EcuException("Not connected")
        val inp = input ?: throw EcuException("Not connected")
        val s = socket ?: throw EcuException("Not connected")
        try {
            Hsfz.write(out, Hsfz.diagnosticRequest(diagAddress, request))
            s.soTimeout = readTimeoutMs
            var pendingDeadline = 0L
            var unexpected = 0
            while (true) {
                val frame = try {
                    Hsfz.readFrame(inp)
                } catch (e: SocketTimeoutException) {
                    if (pendingDeadline != 0L && System.currentTimeMillis() < pendingDeadline) continue
                    throw EcuException(
                        "No response from module 0x%02X within %d ms".format(diagAddress, readTimeoutMs), e
                    )
                }
                when (frame.control) {
                    Hsfz.CTRL_ACK -> continue // gateway echoed our request; the answer follows
                    Hsfz.CTRL_ALIVE_CHECK -> {
                        Hsfz.write(out, Hsfz.aliveCheckResponse())
                        continue
                    }
                    Hsfz.CTRL_DIAGNOSTIC -> {
                        if (frame.source != diagAddress) {
                            // Late answer from a previous request to another module; skip it.
                            if (++unexpected > 16) throw EcuException("ENET stream out of sync")
                            continue
                        }
                        val uds = frame.uds
                        if (Uds.negativeResponseCode(uds) == 0x78) {
                            // Response pending: module is busy (e.g. writing coding data).
                            if (pendingDeadline == 0L) {
                                pendingDeadline = System.currentTimeMillis() + pendingTimeoutMs
                            }
                            s.soTimeout = 5000
                            continue
                        }
                        return uds
                    }
                    else -> {
                        if (frame.isError) {
                            throw EcuException("Gateway rejected request: " + Hsfz.describeError(frame.control))
                        }
                        if (++unexpected > 16) {
                            throw EcuException("ENET stalled on control word 0x%04X".format(frame.control))
                        }
                    }
                }
            }
        } catch (e: EcuException) {
            throw e
        } catch (e: Exception) {
            throw EcuException("ENET link error: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    companion object {
        /** Central gateway (ZGW) diagnostic address on F-series. */
        const val ZGW_ADDRESS = 0x10
    }
}
