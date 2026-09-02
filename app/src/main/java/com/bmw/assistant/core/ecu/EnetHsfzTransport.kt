package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Hsfz
import com.bmw.assistant.core.ecu.uds.Uds

/**
 * ENET transport for F-series cars (incl. the 2010–2016 F10/F11): UDS over BMW's HSFZ framing
 * on TCP port 6801. Works with an ENET cable (phone → USB-C Ethernet adapter → OBD) and with
 * ENET-WiFi adapters, which bridge the same TCP stream over WiFi.
 *
 * Connection sequence:
 *   1. TCP connect to the gateway (ZGW), default 192.168.0.10:6801 for a direct cable; use
 *      [EnetDiscovery] to find the address when the car hands out something else.
 *   2. HSFZ has no routing activation — the first diagnostic frame is the handshake, so a
 *      TesterPresent to the gateway (0x10) proves the link is alive.
 *
 * Per request the UDS bytes are wrapped in a diagnostic frame addressed to the module; the
 * gateway echoes an ACK (control 0x0002), then forwards the module's answer (0x0001). Alive
 * checks (0x0012) are answered inline. Framing, response correlation, "response pending" and
 * the TesterPresent pump all live in [FramedTcpTransport].
 */
class EnetHsfzTransport(
    host: String,
    port: Int = Hsfz.PORT_TCP,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    pendingTimeoutMs: Int = DEFAULT_PENDING_TIMEOUT_MS
) : FramedTcpTransport(host, port, connectTimeoutMs, readTimeoutMs, pendingTimeoutMs) {

    override val supportsCoding: Boolean get() = true
    override val supportsDiagnostics: Boolean get() = true
    override val description: String get() = "ENET (HSFZ) $host:$port"

    override val headerSize: Int get() = Hsfz.HEADER_SIZE
    override val maxPayloadLength: Int get() = Hsfz.MAX_LENGTH
    override val connectHint: String
        get() = "ENET connect to $host:$port failed —"

    override fun payloadLength(header: ByteArray): Int = Hsfz.payloadLength(header)

    override fun requestFrame(diagAddress: Int, uds: ByteArray): ByteArray =
        Hsfz.diagnosticRequest(diagAddress, uds)

    override fun classify(frame: ByteArray): Incoming {
        val parsed = Hsfz.parseFrame(frame) ?: return Incoming.Ignore
        return when (parsed.control) {
            // The gateway echoes our request before forwarding the module's answer.
            Hsfz.CTRL_ACK -> Incoming.Ignore
            Hsfz.CTRL_ALIVE_CHECK -> {
                write(Hsfz.aliveCheckResponse())
                Incoming.Ignore
            }
            Hsfz.CTRL_DIAGNOSTIC -> {
                if (parsed.target != Hsfz.TESTER_ADDRESS) Incoming.Ignore
                else Incoming.Response(parsed.source, parsed.uds)
            }
            else ->
                if (parsed.isError) Incoming.Rejected("Gateway rejected the request: " + Hsfz.describeError(parsed.control))
                else Incoming.Ignore
        }
    }

    override fun handshake() {
        val response = transceive(ZGW_ADDRESS, Uds.testerPresent())
        // A negative response still proves the gateway is speaking HSFZ to us.
        if (!Uds.isPositive(response, Uds.SID_TESTER_PRESENT) && Uds.negativeResponseCode(response) == null) {
            throw EcuException("Gateway did not answer TesterPresent (" + Hex.encode(response) + ")")
        }
    }
}
