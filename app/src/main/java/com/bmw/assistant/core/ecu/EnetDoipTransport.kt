package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Doip
import com.bmw.assistant.core.ecu.uds.Uds
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
 * Per request ([transceive]): the UDS bytes are wrapped in a DoIP diagnostic-message frame
 * addressed to the target module; transport acks and UDS "response pending" (0x78) are absorbed.
 *
 * Multi-frame ISO-TP segmentation is handled by the DoIP gateway for us — over DoIP the UDS
 * payload is carried whole inside one diagnostic message, so we do not need CAN ISO-TP here.
 *
 * Note: many BMW modules also require SecurityAccess (0x27) before a coding write. That
 * seed/key exchange is module-specific and not implemented here; writes that need it will fail
 * with NRC 0x33 (security access denied).
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
    override val supportsDiagnostics: Boolean get() = true

    override fun connect() {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            val inp = s.getInputStream()
            val out = s.getOutputStream()

            // DoIP routing activation handshake.
            Doip.write(out, Doip.routingActivationRequest())
            val res = Doip.readFrame(inp)
            if (res.payloadType != Doip.TYPE_ROUTING_ACTIVATION_RES) {
                throw EcuException("DoIP routing activation failed (type 0x${res.payloadType.toString(16)})")
            }
            // Response code is the 5th byte of the routing-activation response payload; 0x10 = success.
            val code = res.payload.getOrNull(4)?.toInt()?.and(0xFF) ?: -1
            if (code != 0x10) {
                throw EcuException("DoIP routing activation rejected (code 0x${code.toString(16)})")
            }

            socket = s
            input = inp
            output = out
        } catch (e: Exception) {
            runCatching { s.close() }
            socket = null
            input = null
            output = null
            if (e is EcuException) throw e
            throw EcuException(e.message ?: "ENET connect failed", e)
        }
    }

    override fun disconnect() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    /** Sends one UDS request and returns the final UDS response, absorbing acks and 0x78. */
    override fun transceive(diagAddress: Int, request: ByteArray): ByteArray {
        ensureConnected()
        val out = output ?: throw EcuException("Not connected")
        val inp = input ?: throw EcuException("Not connected")
        Doip.write(out, Doip.diagnosticMessage(Doip.TESTER_ADDRESS, diagAddress, request))

        var ignoredUnknown = 0
        while (true) {
            val frame = Doip.readFrame(inp)
            when (frame.payloadType) {
                Doip.TYPE_DIAGNOSTIC_MESSAGE -> {
                    val resp = Doip.udsFromDiagnostic(frame.payload, expectedTarget = diagAddress)
                    // 0x7F xx 0x78 = response pending; keep waiting for the real answer.
                    if (Uds.negativeResponseCode(resp) == 0x78) continue
                    return resp
                }
                Doip.TYPE_DIAGNOSTIC_ACK -> {
                    val ack = Doip.diagnosticAckCode(frame.payload)
                    if (ack != 0) {
                        throw EcuException("DoIP diagnostic ACK error code 0x${ack.toString(16)}")
                    }
                    continue // positive ack, real response follows
                }
                Doip.TYPE_DIAGNOSTIC_NACK ->
                    throw EcuException("DoIP diagnostic NACK from gateway")
                else -> {
                    ignoredUnknown++
                    if (ignoredUnknown > 8) {
                        throw EcuException(
                            "DoIP stalled on unexpected payload type 0x${frame.payloadType.toString(16)}"
                        )
                    }
                }
            }
        }
    }

    private fun ensureConnected() {
        if (!isConnected) throw EcuException("ENET transport not connected")
    }
}
