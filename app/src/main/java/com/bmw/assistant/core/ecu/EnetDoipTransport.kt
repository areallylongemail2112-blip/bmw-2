package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Doip
import com.bmw.assistant.core.ecu.uds.Uds
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * ENET / DoIP (ISO 13400) transport on TCP 13400. This is the framing used by G-series
 * gateways and some late F-series I-levels; a 2012 F10 uses [EnetHsfzTransport] instead. Kept
 * as a selectable option so the app also works on newer cars and DoIP-speaking ENET adapters.
 *
 * Connection sequence:
 *   1. TCP connect to the gateway (default 192.168.0.10:13400) — or the address found by
 *      [EnetDiscovery].
 *   2. DoIP routing activation handshake.
 * Per request ([transceive]): the UDS bytes are wrapped in a DoIP diagnostic-message frame
 * addressed to the target module; transport acks, gateway alive checks and UDS "response
 * pending" (0x78) are absorbed.
 */
class EnetDoipTransport(
    private val host: String,
    private val port: Int = Doip.PORT,
    private val connectTimeoutMs: Int = 4000,
    private val readTimeoutMs: Int = 5000,
    private val pendingTimeoutMs: Int = 30_000
) : EcuTransport {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val lock = Any()
    private val keepAlive = TesterPresentKeepAlive { addr, req -> rawTransceive(addr, req) }

    override val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false
    override val supportsCoding: Boolean get() = true
    override val supportsDiagnostics: Boolean get() = true
    override val description: String get() = "ENET (DoIP) $host:$port"

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
            var res = Doip.readFrame(inp)
            var skipped = 0
            while (res.payloadType != Doip.TYPE_ROUTING_ACTIVATION_RES && skipped++ < 4) {
                if (res.payloadType == Doip.TYPE_ALIVE_CHECK_REQ) Doip.write(out, Doip.aliveCheckResponse())
                res = Doip.readFrame(inp)
            }
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
            keepAlive.start()
        } catch (e: Exception) {
            runCatching { s.close() }
            socket = null
            input = null
            output = null
            if (e is EcuException) throw e
            throw EcuException(
                "DoIP connect to $host:$port failed: ${e.message ?: e.javaClass.simpleName}. " +
                    "A 2010–2016 F10/F11 gateway speaks HSFZ (port 6801) — try the HSFZ option.", e
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

    /** Sends one UDS request and returns the final UDS response, absorbing acks and 0x78. */
    private fun transceiveLocked(diagAddress: Int, request: ByteArray): ByteArray {
        if (!isConnected) throw EcuException("ENET transport not connected")
        val out = output ?: throw EcuException("Not connected")
        val inp = input ?: throw EcuException("Not connected")
        val s = socket ?: throw EcuException("Not connected")
        try {
            Doip.write(out, Doip.diagnosticMessage(Doip.TESTER_ADDRESS, diagAddress, request))
            s.soTimeout = readTimeoutMs
            var pendingDeadline = 0L
            var ignoredUnknown = 0
            while (true) {
                val frame = try {
                    Doip.readFrame(inp)
                } catch (e: SocketTimeoutException) {
                    if (pendingDeadline != 0L && System.currentTimeMillis() < pendingDeadline) continue
                    throw EcuException(
                        "No response from module 0x%02X within %d ms".format(diagAddress, readTimeoutMs), e
                    )
                }
                when (frame.payloadType) {
                    Doip.TYPE_DIAGNOSTIC_MESSAGE -> {
                        val resp = Doip.udsFromDiagnostic(frame.payload, expectedTarget = diagAddress)
                        // 0x7F xx 0x78 = response pending; keep waiting for the real answer.
                        if (Uds.negativeResponseCode(resp) == 0x78) {
                            if (pendingDeadline == 0L) pendingDeadline = System.currentTimeMillis() + pendingTimeoutMs
                            s.soTimeout = 5000
                            continue
                        }
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
                        throw EcuException("DoIP diagnostic NACK from gateway (module 0x%02X unreachable?)".format(diagAddress))
                    Doip.TYPE_ALIVE_CHECK_REQ -> {
                        Doip.write(out, Doip.aliveCheckResponse())
                        continue
                    }
                    Doip.TYPE_GENERIC_NACK ->
                        throw EcuException("DoIP generic NACK code 0x${(frame.payload.getOrNull(0)?.toInt()?.and(0xFF) ?: -1).toString(16)}")
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
        } catch (e: EcuException) {
            throw e
        } catch (e: Exception) {
            throw EcuException("DoIP link error: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
