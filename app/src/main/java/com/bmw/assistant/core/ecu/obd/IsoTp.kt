package com.bmw.assistant.core.ecu.obd

/** A malformed or out-of-order ISO-TP frame. */
class IsoTpException(message: String) : IllegalStateException(message)

/** One raw CAN frame as printed by the adapter: 11-bit id plus up to 8 data bytes. */
data class CanFrame(val id: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is CanFrame && other.id == id && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * id + data.contentHashCode()
}

/**
 * ISO 15765-2 (ISO-TP) segmentation with BMW's **extended addressing** on the D-CAN bus.
 *
 * On an F-series OBD port the tester sends on CAN ID 0x6F1 and every module answers on
 * 0x600 + its diagnostic address (FRM 0x72 → 0x672). The first data byte of each frame is the
 * *extended address*: the target module on requests, 0xF1 (the tester) on responses. That
 * leaves 7 bytes per frame for the ISO-TP PCI + payload:
 *
 *   single frame       [addr] [0x0L] [data × L]              L ≤ 6
 *   first frame        [addr] [0x1H] [LL] [data × 5]         total length = H·256 + LL
 *   consecutive frame  [addr] [0x2N] [data × 6]              N = 1,2,…,15,0,1,…
 *   flow control       [addr] [0x30] [block size] [STmin]
 *
 * **Every transmitted frame is padded to 8 bytes.** F-series module stacks are configured for
 * fixed-length 8-byte frames and a large share of them silently drop a shorter one, so an
 * unpadded flow-control or request frame simply never gets answered.
 *
 * Pure byte helpers only — no I/O — so they are unit-testable.
 */
object IsoTp {
    const val TESTER_CAN_ID = 0x6F1
    const val TESTER_ADDRESS = 0xF1
    const val ECU_CAN_ID_BASE = 0x600

    const val PCI_SINGLE = 0x0
    const val PCI_FIRST = 0x1
    const val PCI_CONSECUTIVE = 0x2
    const val PCI_FLOW_CONTROL = 0x3

    /** Flow status values in a flow-control frame. */
    const val FS_CONTINUE = 0x0
    const val FS_WAIT = 0x1
    const val FS_OVERFLOW = 0x2

    /** Payload bytes available in one frame after the extended-address byte and PCI byte. */
    const val SF_MAX = 6
    const val FF_DATA = 5
    const val CF_DATA = 6

    /** Largest payload ISO-TP can address with a 12-bit length field. */
    const val MAX_PAYLOAD = 4095

    /** Every CAN frame on this bus carries a full 8-byte data field. */
    const val FRAME_SIZE = 8

    fun ecuCanId(diagAddress: Int): Int = ECU_CAN_ID_BASE + (diagAddress and 0xFF)

    /** Pads [data] to the fixed 8-byte CAN data field expected by F-series modules. */
    fun pad(data: ByteArray): ByteArray =
        if (data.size >= FRAME_SIZE) data else data.copyOf(FRAME_SIZE)

    /** Splits [payload] into the 8-byte CAN data fields to transmit. */
    fun buildFrames(diagAddress: Int, payload: ByteArray): List<ByteArray> {
        val addr = diagAddress.toByte()
        if (payload.isEmpty()) throw IsoTpException("Empty UDS request")
        if (payload.size <= SF_MAX) {
            return listOf(pad(byteArrayOf(addr, ((PCI_SINGLE shl 4) or payload.size).toByte()) + payload))
        }
        require(payload.size <= MAX_PAYLOAD) { "ISO-TP payload too large: ${payload.size}" }
        val frames = ArrayList<ByteArray>()
        frames += pad(
            byteArrayOf(
                addr,
                ((PCI_FIRST shl 4) or (payload.size shr 8)).toByte(),
                payload.size.toByte()
            ) + payload.copyOfRange(0, FF_DATA)
        )
        var pos = FF_DATA
        var seq = 1
        while (pos < payload.size) {
            val end = minOf(pos + CF_DATA, payload.size)
            frames += pad(
                byteArrayOf(addr, ((PCI_CONSECUTIVE shl 4) or (seq and 0x0F)).toByte()) +
                    payload.copyOfRange(pos, end)
            )
            pos = end
            seq = (seq + 1) and 0x0F
        }
        return frames
    }

    /**
     * Flow-control frame telling the module how to send the rest of its answer.
     * @param blockSize 0 = send everything without waiting for another flow control.
     * @param stMinMs minimum separation between consecutive frames, 0..127 ms.
     */
    fun flowControl(diagAddress: Int, blockSize: Int = 0, stMinMs: Int = DEFAULT_ST_MIN_MS): ByteArray {
        require(stMinMs in 0..0x7F) { "STmin must be 0..127 ms, was $stMinMs" }
        return pad(
            byteArrayOf(
                diagAddress.toByte(),
                ((PCI_FLOW_CONTROL shl 4) or FS_CONTINUE).toByte(),
                blockSize.toByte(),
                stMinMs.toByte()
            )
        )
    }

    /** What kind of frame a received data field is (after the extended-address byte). */
    fun pciType(data: ByteArray): Int = if (data.size < 2) -1 else (data[1].toInt() and 0xF0) shr 4

    /** Flow status (continue / wait / overflow) of a received flow-control frame. */
    fun flowStatus(data: ByteArray): Int = if (data.size < 2) -1 else data[1].toInt() and 0x0F

    /** Block size of a received flow-control frame; 0 means "send everything". */
    fun flowControlBlockSize(data: ByteArray): Int = data.getOrNull(2)?.toInt()?.and(0xFF) ?: 0

    /**
     * Separation time requested by a flow-control frame, in milliseconds. Values 0xF1..0xF9 mean
     * 100..900 microseconds, which round up to 1 ms; anything else reserved is treated as 0.
     */
    fun flowControlStMinMs(data: ByteArray): Long {
        val raw = data.getOrNull(3)?.toInt()?.and(0xFF) ?: return 0
        return when {
            raw <= 0x7F -> raw.toLong()
            raw in 0xF1..0xF9 -> 1L
            else -> 0L
        }
    }

    /** Total payload length announced by a first frame. */
    fun firstFrameLength(data: ByteArray): Int =
        ((data[1].toInt() and 0x0F) shl 8) or (data[2].toInt() and 0xFF)

    /** True when [data] is a "response pending" (0x7F xx 0x78) single frame. */
    fun isResponsePending(data: ByteArray): Boolean =
        pciType(data) == PCI_SINGLE && data.size >= 5 &&
            (data[2].toInt() and 0xFF) == 0x7F && (data[4].toInt() and 0xFF) == 0x78

    /** Incrementally reassembles a segmented response. */
    class Reassembler {
        private var expected = -1
        private var buffer: ByteArray = ByteArray(0)
        private var nextSeq = 1

        val isComplete: Boolean get() = expected >= 0 && buffer.size >= expected
        val payload: ByteArray get() = if (expected < 0) ByteArray(0) else buffer.copyOf(expected)

        /**
         * Feeds one received frame's data field (with the leading extended-address byte).
         * @return true when this frame was a first frame (caller must send flow control).
         * @throws IsoTpException on a malformed or out-of-order frame.
         */
        fun feed(data: ByteArray): Boolean {
            when (pciType(data)) {
                PCI_SINGLE -> {
                    val len = data[1].toInt() and 0x0F
                    if (len == 0 || len > SF_MAX) {
                        throw IsoTpException("ISO-TP single frame with invalid length $len")
                    }
                    if (data.size < 2 + len) {
                        throw IsoTpException("ISO-TP single frame truncated (${data.size} bytes, needs ${2 + len})")
                    }
                    expected = len
                    buffer = data.copyOfRange(2, 2 + len)
                    return false
                }
                PCI_FIRST -> {
                    if (data.size < 3) throw IsoTpException("ISO-TP first frame too short")
                    val len = firstFrameLength(data)
                    if (len <= SF_MAX) {
                        throw IsoTpException("ISO-TP first frame announces $len bytes (must be > $SF_MAX)")
                    }
                    expected = len
                    buffer = data.copyOfRange(3, data.size)
                    nextSeq = 1
                    return true
                }
                PCI_CONSECUTIVE -> {
                    if (expected < 0) throw IsoTpException("ISO-TP consecutive frame without a first frame")
                    val seq = data[1].toInt() and 0x0F
                    if (seq != nextSeq) {
                        throw IsoTpException("ISO-TP sequence error: got $seq, expected $nextSeq")
                    }
                    nextSeq = (nextSeq + 1) and 0x0F
                    buffer += data.copyOfRange(2, data.size)
                    if (buffer.size > expected) buffer = buffer.copyOf(expected)
                    return false
                }
                else -> return false
            }
        }
    }

    /**
     * Parses one ELM327 response line printed with headers on, e.g.
     * `672F10662F1500F25F0` → (canId=0x672, data=F1 06 62 F1 50 0F 25 F0). Spaces are tolerated
     * for adapters left in `ATS1`. Returns null for status lines (`OK`, `NO DATA`, `SEARCHING…`).
     */
    fun parseElmLine(line: String): CanFrame? {
        val clean = line.trim().replace(" ", "")
        if (clean.length < 5) return null
        if (!clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        // 11-bit header = 3 hex digits, then whole bytes.
        if ((clean.length - 3) % 2 != 0) return null
        val dataLength = (clean.length - 3) / 2
        if (dataLength > FRAME_SIZE) return null
        val id = clean.substring(0, 3).toInt(16)
        val data = ByteArray(dataLength) { i ->
            clean.substring(3 + i * 2, 5 + i * 2).toInt(16).toByte()
        }
        return CanFrame(id, data)
    }

    private const val DEFAULT_ST_MIN_MS = 10
}
