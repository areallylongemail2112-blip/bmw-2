package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Doip
import java.net.SocketTimeoutException

/**
 * ENET / DoIP (ISO 13400) transport on TCP 13400. This is the framing used by G-series gateways
 * and some late F-series I-levels; a 2012 F10 uses [EnetHsfzTransport] instead. Kept as a
 * selectable option so the app also works on newer cars and DoIP-speaking ENET adapters.
 *
 * Connection sequence:
 *   1. TCP connect to the gateway (default 192.168.0.10:13400), or the address found by
 *      [EnetDiscovery].
 *   2. DoIP routing activation handshake.
 *
 * Per request the UDS bytes go out in a diagnostic-message frame; transport acks, alive checks
 * and "response pending" are absorbed by [FramedTcpTransport].
 */
class EnetDoipTransport(
    host: String,
    port: Int = Doip.PORT,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    pendingTimeoutMs: Int = DEFAULT_PENDING_TIMEOUT_MS
) : FramedTcpTransport(host, port, connectTimeoutMs, readTimeoutMs, pendingTimeoutMs) {

    override val supportsCoding: Boolean get() = true
    override val supportsDiagnostics: Boolean get() = true
    override val description: String get() = "ENET (DoIP) $host:$port"

    override val headerSize: Int get() = Doip.HEADER_SIZE
    override val maxPayloadLength: Int get() = Doip.MAX_PAYLOAD_LENGTH
    override val connectHint: String
        get() = "DoIP connect to $host:$port failed — a 2010–2016 F10/F11 speaks HSFZ (port 6801), try that option;"

    override fun payloadLength(header: ByteArray): Int = Doip.payloadLength(header)

    override fun requestFrame(diagAddress: Int, uds: ByteArray): ByteArray =
        Doip.diagnosticMessage(Doip.TESTER_ADDRESS, diagAddress, uds)

    override fun classify(frame: ByteArray): Incoming {
        val parsed = Doip.parse(frame) ?: return Incoming.Ignore
        return when (parsed.payloadType) {
            Doip.TYPE_DIAGNOSTIC_MESSAGE -> {
                val payload = parsed.payload
                if (payload.size < 4) return Incoming.Ignore
                val source = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                // A frame from another module belongs to an earlier request; skip it rather
                // than failing the request in flight.
                Incoming.Response(source, payload.copyOfRange(4, payload.size))
            }
            Doip.TYPE_DIAGNOSTIC_ACK -> {
                val code = Doip.diagnosticAckCode(parsed.payload)
                if (code != 0) Incoming.Rejected("Gateway rejected the message (ACK code 0x${code.toString(16)})")
                else Incoming.Ignore
            }
            Doip.TYPE_DIAGNOSTIC_NACK ->
                Incoming.Rejected("Gateway could not reach the module (DoIP diagnostic NACK)")
            Doip.TYPE_ALIVE_CHECK_REQ -> {
                write(Doip.aliveCheckResponse())
                Incoming.Ignore
            }
            Doip.TYPE_GENERIC_NACK -> {
                val code = parsed.payload.getOrNull(0)?.toInt()?.and(0xFF) ?: -1
                Incoming.Rejected("Gateway rejected the message (generic NACK 0x${code.toString(16)})")
            }
            else -> Incoming.Ignore
        }
    }

    override fun handshake() {
        write(Doip.routingActivationRequest())
        val deadline = System.currentTimeMillis() + ROUTING_ACTIVATION_TIMEOUT_MS
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw EcuException("DoIP routing activation timed out")
            }
            val frame = try {
                readHandshakeFrame(deadline)
            } catch (e: SocketTimeoutException) {
                throw EcuException("DoIP routing activation timed out", e)
            }
            val parsed = Doip.parse(frame) ?: continue
            when (parsed.payloadType) {
                Doip.TYPE_ALIVE_CHECK_REQ -> write(Doip.aliveCheckResponse())
                Doip.TYPE_ROUTING_ACTIVATION_RES -> {
                    // Response code is the 5th payload byte; 0x10 = routing activated.
                    val code = parsed.payload.getOrNull(4)?.toInt()?.and(0xFF) ?: -1
                    if (code != ROUTING_ACTIVATION_SUCCESS) {
                        throw EcuException("DoIP routing activation rejected (code 0x${code.toString(16)})")
                    }
                    return
                }
                Doip.TYPE_GENERIC_NACK ->
                    throw EcuException("Gateway rejected the routing activation request")
                else -> Unit // ignore anything else during the handshake
            }
        }
    }

    private companion object {
        const val ROUTING_ACTIVATION_TIMEOUT_MS = 5000L
        const val ROUTING_ACTIVATION_SUCCESS = 0x10
    }
}
