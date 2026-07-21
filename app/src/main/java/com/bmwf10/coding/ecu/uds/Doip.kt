package com.bmwf10.coding.ecu.uds

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal DoIP (Diagnostics over IP, ISO 13400) framing — the protocol E-Sys/ISTA use over
 * an ENET cable to talk to F-series cars. A DoIP message is an 8-byte header followed by a
 * payload:
 *
 *   `protoVer | inverseProtoVer | payloadType (2) | payloadLen (4) | payload`
 *
 * We use two payload types:
 *   0x0005 Routing activation request/response (the DoIP "handshake")
 *   0x8001 Diagnostic message: `sourceAddr (2) | targetAddr (2) | UDS bytes`
 */
object Doip {
    const val PROTOCOL_VERSION = 0x02
    const val PORT = 13400
    private const val MAX_PAYLOAD_LENGTH = 1024 * 1024

    const val TYPE_ROUTING_ACTIVATION_REQ = 0x0005
    const val TYPE_ROUTING_ACTIVATION_RES = 0x0006
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
        // payload: [sourceAddr:2][activationType:1][reserved:4]
        val payload = byteArrayOf(
            (sourceAddr shr 8).toByte(), sourceAddr.toByte(),
            0x00, // activation type: default
            0x00, 0x00, 0x00, 0x00
        )
        return header(TYPE_ROUTING_ACTIVATION_REQ, payload.size) + payload
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
        val version = head[0].toInt() and 0xFF
        val inverseVersion = head[1].toInt() and 0xFF
        if ((version xor inverseVersion) != 0xFF) {
            throw IOException("Invalid DoIP protocol version header")
        }
        val payloadType = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        val len = ((head[4].toLong() and 0xFF) shl 24) or
            ((head[5].toLong() and 0xFF) shl 16) or
            ((head[6].toLong() and 0xFF) shl 8) or
            (head[7].toLong() and 0xFF)
        if (len > MAX_PAYLOAD_LENGTH) {
            throw IOException("DoIP payload length $len exceeds the supported limit")
        }
        val payload = ByteArray(len.toInt())
        if (len > 0) din.readFully(payload)
        return Frame(payloadType, payload)
    }

    fun write(output: OutputStream, bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    /** Strips the 4-byte source/target address prefix from a diagnostic-message payload. */
    fun udsFromDiagnostic(payload: ByteArray): ByteArray =
        if (payload.size > 4) payload.copyOfRange(4, payload.size) else ByteArray(0)
}
