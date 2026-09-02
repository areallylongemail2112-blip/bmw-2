package com.bmw.assistant.core.ecu.uds

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal DoIP (Diagnostics over IP, ISO 13400) framing — the protocol E-Sys/ISTA use over
 * an ENET cable to talk to F-series cars. A DoIP message is an 8-byte header followed by a
 * payload:
 *
 *   protoVer, ~protoVer, payloadType (2), payloadLen (4), payload...
 *
 * We use two payload types:
 *   0x0005 Routing activation request/response (the DoIP "handshake")
 *   0x8001 Diagnostic message: sourceAddr (2), targetAddr (2), udsBytes...
 */
object Doip {
    const val PROTOCOL_VERSION = 0x02
    const val PORT = 13400

    /** Bytes before the payload: version, inverse version, 2-byte type, 4-byte length. */
    const val HEADER_SIZE = 8

    /** Hard cap so a corrupted length field cannot force an OOM. */
    const val MAX_PAYLOAD_LENGTH = 64 * 1024

    const val TYPE_GENERIC_NACK = 0x0000
    const val TYPE_VEHICLE_IDENT_REQ = 0x0001
    const val TYPE_VEHICLE_ANNOUNCEMENT = 0x0004
    const val TYPE_ROUTING_ACTIVATION_REQ = 0x0005
    const val TYPE_ROUTING_ACTIVATION_RES = 0x0006
    const val TYPE_ALIVE_CHECK_REQ = 0x0007
    const val TYPE_ALIVE_CHECK_RES = 0x0008
    const val TYPE_DIAGNOSTIC_MESSAGE = 0x8001
    const val TYPE_DIAGNOSTIC_ACK = 0x8002
    const val TYPE_DIAGNOSTIC_NACK = 0x8003

    /** Tester (client) source address. 0x0E80 is the conventional external-tester address. */
    const val TESTER_ADDRESS = 0x0E80

    private fun header(payloadType: Int, payloadLen: Int): ByteArray = byteArrayOf(
        PROTOCOL_VERSION.toByte(),
        (PROTOCOL_VERSION.inv()).toByte(),
        (payloadType shr 8).toByte(),
        payloadType.toByte(),
        (payloadLen shr 24).toByte(),
        (payloadLen shr 16).toByte(),
        (payloadLen shr 8).toByte(),
        payloadLen.toByte()
    )

    fun routingActivationRequest(sourceAddr: Int = TESTER_ADDRESS): ByteArray {
        // payload: sourceAddr (2), activationType (1), reserved (4)
        val payload = byteArrayOf(
            (sourceAddr shr 8).toByte(), sourceAddr.toByte(),
            0x00, // activation type: default
            0x00, 0x00, 0x00, 0x00
        )
        return header(TYPE_ROUTING_ACTIVATION_REQ, payload.size) + payload
    }

    /** UDP vehicle identification request (broadcast to [PORT]); gateways answer with 0x0004. */
    fun vehicleIdentificationRequest(): ByteArray = header(TYPE_VEHICLE_IDENT_REQ, 0)

    /** Reply to a gateway alive check: our source address. */
    fun aliveCheckResponse(sourceAddr: Int = TESTER_ADDRESS): ByteArray =
        header(TYPE_ALIVE_CHECK_RES, 2) + byteArrayOf((sourceAddr shr 8).toByte(), sourceAddr.toByte())

    /** VIN from a vehicle announcement payload (first 17 bytes), or null. */
    fun vinFromAnnouncement(payload: ByteArray): String? {
        if (payload.size < 17) return null
        val vin = String(payload, 0, 17, Charsets.ISO_8859_1)
        return if (vin.all { it.isLetterOrDigit() }) vin else null
    }

    /** Payload length announced by a [HEADER_SIZE]-byte header. */
    fun payloadLength(header: ByteArray): Int {
        if (header.size < HEADER_SIZE) return -1
        return ((header[4].toInt() and 0xFF) shl 24) or
            ((header[5].toInt() and 0xFF) shl 16) or
            ((header[6].toInt() and 0xFF) shl 8) or
            (header[7].toInt() and 0xFF)
    }

    /** Parses a complete datagram into a frame, or null when malformed. */
    fun parse(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE) return null
        val protoVer = bytes[0].toInt() and 0xFF
        val inverse = bytes[1].toInt() and 0xFF
        if (protoVer != PROTOCOL_VERSION || inverse != (PROTOCOL_VERSION.inv() and 0xFF)) return null
        val payloadType = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val len = payloadLength(bytes)
        if (len < 0 || HEADER_SIZE + len > bytes.size) return null
        return Frame(payloadType, bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + len))
    }

    fun diagnosticMessage(source: Int, target: Int, uds: ByteArray): ByteArray {
        val payload = byteArrayOf(
            (source shr 8).toByte(), source.toByte(),
            (target shr 8).toByte(), target.toByte()
        ) + uds
        return header(TYPE_DIAGNOSTIC_MESSAGE, payload.size) + payload
    }

    data class Frame(val payloadType: Int, val payload: ByteArray)

    /** Blocking read of one DoIP frame from [input]. */
    fun readFrame(input: InputStream): Frame {
        val din = DataInputStream(input)
        val head = ByteArray(8)
        din.readFully(head)
        val protoVer = head[0].toInt() and 0xFF
        val inverse = head[1].toInt() and 0xFF
        if (protoVer != PROTOCOL_VERSION || inverse != ((PROTOCOL_VERSION.inv()) and 0xFF)) {
            throw IllegalArgumentException(
                "Invalid DoIP protocol version 0x${protoVer.toString(16)}/~0x${inverse.toString(16)}"
            )
        }
        val payloadType = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        val len = ((head[4].toInt() and 0xFF) shl 24) or
            ((head[5].toInt() and 0xFF) shl 16) or
            ((head[6].toInt() and 0xFF) shl 8) or
            (head[7].toInt() and 0xFF)
        if (len < 0 || len > MAX_PAYLOAD_LENGTH) {
            throw IllegalArgumentException("DoIP payload length out of range: $len")
        }
        val payload = ByteArray(len)
        if (len > 0) din.readFully(payload)
        return Frame(payloadType, payload)
    }

    fun write(output: OutputStream, bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /**
     * Strips the 4-byte source/target address prefix from a diagnostic-message payload.
     * When [expectedTarget] is set, verifies the response source matches that ECU address.
     */
    fun udsFromDiagnostic(payload: ByteArray, expectedTarget: Int? = null): ByteArray {
        if (payload.size < 4) return ByteArray(0)
        if (expectedTarget != null) {
            val source = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            if (source != expectedTarget) {
                throw IllegalArgumentException(
                    "DoIP response from unexpected address 0x${source.toString(16)} " +
                        "(expected 0x${expectedTarget.toString(16)})"
                )
            }
        }
        return payload.copyOfRange(4, payload.size)
    }

    /** ACK/NACK code is the 5th byte of the diagnostic ACK/NACK payload (0 = OK). */
    fun diagnosticAckCode(payload: ByteArray): Int =
        payload.getOrNull(4)?.toInt()?.and(0xFF) ?: -1
}
