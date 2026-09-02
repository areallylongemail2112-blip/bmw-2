package com.bmw.assistant.core.ecu.uds

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * BMW HSFZ ("High-Speed-Fahrzeug-Zugang") framing — the ENET protocol spoken by every
 * F-series gateway (ZGW) from 2008 until DoIP replaced it on the G-series. A **2012 F10 talks
 * HSFZ on TCP port 6801**, not DoIP 13400; E-Sys, ISTA and BimmerCode all use this framing over
 * an ENET cable or ENET-WiFi adapter.
 *
 * Frame layout (all big-endian):
 *
 *   length (4)   = number of bytes after the control word (= 2 address bytes + UDS payload)
 *   control (2)  = 0x0001 diagnostic request/response, 0x0002 acknowledge (echo of our request),
 *                  0x0011 vehicle identification, 0x0012 alive check, 0x0040.. errors
 *   source (1)   = tester address 0xF4 on requests / ECU address on responses
 *   target (1)   = ECU diagnostic address on requests / 0xF4 on responses
 *   payload      = raw UDS bytes
 *
 * Discovery: a UDP datagram `00 00 00 00 00 11` broadcast to port 6811 makes every gateway on
 * the link answer with control 0x0011 and an identification string (contains the VIN).
 *
 * References: scapy `contrib/automotive/bmw/hsfz.py`, dissec.to knowledge base ("HSFZ").
 */
object Hsfz {
    const val PORT_TCP = 6801
    const val PORT_UDP_IDENT = 6811

    /** Tester (diagnostic client) address used by E-Sys/ISTA. */
    const val TESTER_ADDRESS = 0xF4

    const val CTRL_DIAGNOSTIC = 0x0001
    const val CTRL_ACK = 0x0002
    const val CTRL_TERMINAL15 = 0x0010
    const val CTRL_VEHICLE_IDENT = 0x0011
    const val CTRL_ALIVE_CHECK = 0x0012
    const val CTRL_STATUS_INQUIRY = 0x0013
    const val CTRL_ERR_INCORRECT_TESTER_ADDRESS = 0x0040
    const val CTRL_ERR_INCORRECT_CONTROL_WORD = 0x0041
    const val CTRL_ERR_INCORRECT_FORMAT = 0x0042
    const val CTRL_ERR_INCORRECT_DEST_ADDRESS = 0x0043
    const val CTRL_ERR_MESSAGE_TOO_LARGE = 0x0044
    const val CTRL_ERR_DIAG_APP_NOT_READY = 0x0045
    const val CTRL_ERR_OUT_OF_MEMORY = 0x00FF

    /** Hard cap so a corrupted length field cannot force an OOM. */
    const val MAX_LENGTH = 64 * 1024

    data class Frame(val control: Int, val data: ByteArray) {
        /** Source address of a diagnostic/ack frame (the ECU on responses). */
        val source: Int get() = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1
        /** Target address of a diagnostic/ack frame (0xF4 on responses). */
        val target: Int get() = if (data.size > 1) data[1].toInt() and 0xFF else -1
        /** UDS payload of a diagnostic/ack frame. */
        val uds: ByteArray get() = if (data.size > 2) data.copyOfRange(2, data.size) else ByteArray(0)
        val isError: Boolean get() = control >= CTRL_ERR_INCORRECT_TESTER_ADDRESS
    }

    fun frame(control: Int, data: ByteArray): ByteArray {
        val len = data.size
        return byteArrayOf(
            (len shr 24).toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte(),
            (control shr 8).toByte(), control.toByte()
        ) + data
    }

    /** A UDS request from the tester to ECU [target]. */
    fun diagnosticRequest(target: Int, uds: ByteArray, source: Int = TESTER_ADDRESS): ByteArray =
        frame(CTRL_DIAGNOSTIC, byteArrayOf(source.toByte(), target.toByte()) + uds)

    /** Reply to a gateway alive check: control 0x0012 carrying our tester address. */
    fun aliveCheckResponse(source: Int = TESTER_ADDRESS): ByteArray =
        frame(CTRL_ALIVE_CHECK, byteArrayOf(0x00, source.toByte()))

    /** UDP identification request broadcast to [PORT_UDP_IDENT]. */
    fun identificationRequest(): ByteArray = frame(CTRL_VEHICLE_IDENT, ByteArray(0))

    /** Blocking read of one HSFZ frame from [input]. */
    fun readFrame(input: InputStream): Frame {
        val din = DataInputStream(input)
        val head = ByteArray(6)
        din.readFully(head)
        val len = ((head[0].toInt() and 0xFF) shl 24) or
            ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or
            (head[3].toInt() and 0xFF)
        val control = ((head[4].toInt() and 0xFF) shl 8) or (head[5].toInt() and 0xFF)
        if (len < 0 || len > MAX_LENGTH) {
            throw IllegalArgumentException("HSFZ length out of range: $len")
        }
        val data = ByteArray(len)
        if (len > 0) din.readFully(data)
        return Frame(control, data)
    }

    /** Parses a frame from a complete datagram (UDP identification replies). */
    fun parse(bytes: ByteArray): Frame? {
        if (bytes.size < 6) return null
        val len = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        val control = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        if (len < 0 || 6 + len > bytes.size) return null
        return Frame(control, bytes.copyOfRange(6, 6 + len))
    }

    fun write(output: OutputStream, bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    fun describeError(control: Int): String = when (control) {
        CTRL_ERR_INCORRECT_TESTER_ADDRESS -> "incorrect tester address"
        CTRL_ERR_INCORRECT_CONTROL_WORD -> "incorrect control word"
        CTRL_ERR_INCORRECT_FORMAT -> "incorrect format"
        CTRL_ERR_INCORRECT_DEST_ADDRESS -> "incorrect destination address (module not on this gateway)"
        CTRL_ERR_MESSAGE_TOO_LARGE -> "message too large"
        CTRL_ERR_DIAG_APP_NOT_READY -> "diagnostic application not ready"
        CTRL_ERR_OUT_OF_MEMORY -> "gateway out of memory"
        else -> "control word 0x%04X".format(control)
    }

    /**
     * Pulls a 17-character VIN out of an identification string such as
     * `DIAGADR10DIAGADR10VIN=WBAFR9C5XBC123456...`. Returns null if none is present.
     */
    fun vinFromIdentification(data: ByteArray): String? {
        val text = String(data, Charsets.ISO_8859_1)
        val idx = text.indexOf("VIN")
        val start = if (idx >= 0) {
            var i = idx + 3
            while (i < text.length && (text[i] == '=' || text[i] == ':' || text[i] == ' ')) i++
            i
        } else 0
        val candidate = text.drop(start).take(17)
        return if (candidate.length == 17 && candidate.all { it.isLetterOrDigit() }) candidate else null
    }
}
