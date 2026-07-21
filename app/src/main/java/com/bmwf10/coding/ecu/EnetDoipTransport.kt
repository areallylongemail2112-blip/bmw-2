package com.bmwf10.coding.ecu

import com.bmwf10.coding.ecu.uds.Doip
import com.bmwf10.coding.ecu.uds.Uds
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Real ENET / DoIP transport. This is the coding-capable path for BMW F-series cars: a
 * laptop-or-phone connects to the car's OBD-II Ethernet pins (via an ENET cable) or the
 * gateway's WiFi, and speaks DoIP (ISO 13400) carrying UDS (ISO 14229) diagnostic messages.
 *
 * Connection sequence implemented here:
 *   1. TCP connect to the gateway (default 192.168.0.10:13400).
 *   2. DoIP routing activation handshake.
 *   3. Per request: extended diagnostic session, then RDBI/WDBI wrapped in DoIP
 *      diagnostic-message frames addressed to the target module.
 *
 * Multi-frame ISO-TP segmentation is handled by the DoIP gateway for us — over DoIP the UDS
 * payload is carried whole inside one diagnostic message, so we do not need CAN ISO-TP here.
 */
class EnetDoipTransport(
    private val host: String,
    private val port: Int = Doip.PORT,
    private val connectTimeoutMs: Int = 4000,
    private val readTimeoutMs: Int = 5000
) : EcuTransport {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false
    override val supportsCoding: Boolean get() = true

    override fun connect() {
        // Any failure after the socket is opened must close it, or a timeout/IO error
        // during the handshake (e.g. the car is off and never answers) leaks the fd.
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()

            // DoIP routing activation handshake.
            Doip.write(output!!, Doip.routingActivationRequest())
            val res = Doip.readFrame(input!!)
            if (res.payloadType != Doip.TYPE_ROUTING_ACTIVATION_RES) {
                throw EcuException("DoIP routing activation failed (type 0x${res.payloadType.toString(16)})")
            }
            // Response code is the 5th byte of the routing-activation response payload; 0x10 = success.
            val code = res.payload.getOrNull(4)?.toInt()?.and(0xFF) ?: -1
            if (code != 0x10) {
                throw EcuException("DoIP routing activation rejected (code 0x${code.toString(16)})")
            }
        } catch (e: Exception) {
            disconnect()
            throw if (e is EcuException) e
            else EcuException("ENET connect to $host:$port failed: ${e.message}", e)
        }
    }

    override fun disconnect() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    private fun ensureExtendedSession(diagAddress: Int) {
        val resp = requestUnlocked(diagAddress, Uds.sessionControl(Uds.SESSION_EXTENDED))
        if (!Uds.isPositive(resp, Uds.SID_DIAGNOSTIC_SESSION_CONTROL)) {
            throw EcuException("Could not open extended session: " + describe(resp))
        }
    }

    @Synchronized
    override fun readCodingBlock(diagAddress: Int, did: Int): ByteArray {
        ensureConnected()
        ensureExtendedSession(diagAddress)
        val resp = requestUnlocked(diagAddress, Uds.readDataByIdentifier(did))
        if (!Uds.isPositive(resp, Uds.SID_READ_DATA_BY_IDENTIFIER)) {
            throw EcuException("ReadDataByIdentifier failed: " + describe(resp))
        }
        // strip echoed [SID+0x40][DID hi][DID lo]
        return if (resp.size > 3) resp.copyOfRange(3, resp.size) else ByteArray(0)
    }

    @Synchronized
    override fun writeCodingBlock(diagAddress: Int, did: Int, data: ByteArray) {
        ensureConnected()
        ensureExtendedSession(diagAddress)
        val resp = requestUnlocked(diagAddress, Uds.writeDataByIdentifier(did, data))
        if (!Uds.isPositive(resp, Uds.SID_WRITE_DATA_BY_IDENTIFIER)) {
            throw EcuException("WriteDataByIdentifier failed: " + describe(resp))
        }
    }

    /**
     * Sends one UDS request and returns the final UDS response, absorbing 0x78 "pending".
     * Caller must hold the instance monitor (see [readCodingBlock]/[writeCodingBlock]) so
     * concurrent callers cannot interleave frames on the shared TCP socket.
     * Bound by a wall-clock deadline and max pending/unknown-frame counts so alive-check
     * traffic (or a silent car) cannot hang the request forever.
     */
    private fun requestUnlocked(diagAddress: Int, uds: ByteArray): ByteArray {
        val out = output ?: throw EcuException("Not connected")
        val inp = input ?: throw EcuException("Not connected")
        Doip.write(out, Doip.diagnosticMessage(Doip.TESTER_ADDRESS, diagAddress, uds))

        val deadlineNs = System.nanoTime() + readTimeoutMs * 1_000_000L * 4
        var pendingCount = 0
        var ignoredFrames = 0
        while (true) {
            if (System.nanoTime() > deadlineNs) {
                throw EcuException("DoIP response timed out waiting for ECU 0x${"%04X".format(diagAddress)}")
            }
            val frame = Doip.readFrame(inp)
            when (frame.payloadType) {
                Doip.TYPE_DIAGNOSTIC_MESSAGE -> {
                    // Diagnostic payload: source(2) target(2) uds... — require the source to
                    // be the ECU we addressed so we don't accept a stale/wrong response.
                    if (frame.payload.size >= 4) {
                        val src = ((frame.payload[0].toInt() and 0xFF) shl 8) or
                            (frame.payload[1].toInt() and 0xFF)
                        if (src != diagAddress) {
                            if (++ignoredFrames > 16) {
                                throw EcuException("DoIP received too many frames from unexpected ECUs")
                            }
                            continue
                        }
                    }
                    val resp = Doip.udsFromDiagnostic(frame.payload)
                    // 0x7F xx 0x78 = response pending; keep waiting for the real answer.
                    if (Uds.negativeResponseCode(resp) == 0x78) {
                        if (++pendingCount > 32) {
                            throw EcuException("DoIP response pending exceeded limit")
                        }
                        continue
                    }
                    return resp
                }
                Doip.TYPE_DIAGNOSTIC_ACK -> continue // positive ack, real response follows
                Doip.TYPE_DIAGNOSTIC_NACK ->
                    throw EcuException("DoIP diagnostic NACK from gateway")
                else -> {
                    if (++ignoredFrames > 16) {
                        throw EcuException(
                            "DoIP received unexpected frame type 0x${frame.payloadType.toString(16)}"
                        )
                    }
                }
            }
        }
    }

    private fun ensureConnected() {
        if (!isConnected) throw EcuException("ENET transport not connected")
    }

    private fun describe(resp: ByteArray): String =
        Uds.negativeResponseCode(resp)?.let { Uds.describeNrc(it) }
            ?: ("unexpected response " + Hex.encode(resp))
}
