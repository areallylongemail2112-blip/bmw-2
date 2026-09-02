package com.bmw.assistant.core.ecu.obd

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

    /** Payload bytes available in one frame after the extended-address byte and PCI byte. */
    const val SF_MAX = 6
    private const val FF_DATA = 5
    private const val CF_DATA = 6

    fun ecuCanId(diagAddress: Int): Int = ECU_CAN_ID_BASE + (diagAddress and 0xFF)

    /** Splits [payload] into the CAN data fields to transmit (each ≤ 8 bytes, unpadded). */
    fun buildFrames(diagAddress: Int, payload: ByteArray): List<ByteArray> {
        val addr = diagAddress.toByte()
        if (payload.size <= SF_MAX) {
            return listOf(byteArrayOf(addr, ((PCI_SINGLE shl 4) or payload.size).toByte()) + payload)
        }
        require(payload.size <= 4095) { "ISO-TP payload too large: ${payload.size}" }
        val frames = ArrayList<ByteArray>()
        frames += byteArrayOf(
            addr,
            ((PCI_FIRST shl 4) or (payload.size shr 8)).toByte(),
            payload.size.toByte()
        ) + payload.copyOfRange(0, FF_DATA)
        var pos = FF_DATA
        var seq = 1
        while (pos < payload.size) {
            val end = minOf(pos + CF_DATA, payload.size)
            frames += byteArrayOf(addr, ((PCI_CONSECUTIVE shl 4) or (seq and 0x0F)).toByte()) +
                payload.copyOfRange(pos, end)
            pos = end
            seq = (seq + 1) and 0x0F
        }
        return frames
    }

    /** Flow-control frame telling the module it may send everything, spaced [stMinMs] apart. */
    fun flowControl(diagAddress: Int, blockSize: Int = 0, stMinMs: Int = 10): ByteArray =
        byteArrayOf(diagAddress.toByte(), (PCI_FLOW_CONTROL shl 4).toByte(), blockSize.toByte(), stMinMs.toByte())

    /** What kind of frame a received data field is (after the extended-address byte). */
    fun pciType(data: ByteArray): Int = if (data.size < 2) -1 else (data[1].toInt() and 0xF0) shr 4

    /** Total payload length announced by a first frame. */
    fun firstFrameLength(data: ByteArray): Int =
        ((data[1].toInt() and 0x0F) shl 8) or (data[2].toInt() and 0xFF)

    /** Incrementally reassembles a segmented response. */
    class Reassembler {
        private var expected = -1
        private var buffer: ByteArray = ByteArray(0)
        private var nextSeq = 1

        val isComplete: Boolean get() = expected >= 0 && buffer.size >= expected
        val payload: ByteArray get() = buffer.copyOf(expected)

        /**
         * Feeds one received frame's data field (with the leading extended-address byte).
         * @return true when this frame was a first frame (caller must send flow control).
         */
        fun feed(data: ByteArray): Boolean {
            when (pciType(data)) {
                PCI_SINGLE -> {
                    val len = data[1].toInt() and 0x0F
                    expected = len
                    buffer = data.copyOfRange(2, minOf(2 + len, data.size))
                    return false
                }
                PCI_FIRST -> {
                    expected = firstFrameLength(data)
                    buffer = data.copyOfRange(3, data.size)
                    nextSeq = 1
                    return true
                }
                PCI_CONSECUTIVE -> {
                    val seq = data[1].toInt() and 0x0F
                    if (seq != nextSeq) throw IllegalStateException("ISO-TP sequence error: got $seq expected $nextSeq")
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
     * Parses one ELM327 response line printed with headers on and spaces off, e.g.
     * `672F10662F1500F25F0` → (canId=0x672, data=F1 06 62 F1 50 0F 25 F0). Returns null for
     * status lines (`OK`, `NO DATA`, `SEARCHING...`, ...).
     */
    fun parseElmLine(line: String): Pair<Int, ByteArray>? {
        val clean = line.trim().replace(" ", "")
        if (clean.length < 5) return null
        if (!clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        // 11-bit header = 3 hex digits, then whole bytes.
        if ((clean.length - 3) % 2 != 0) return null
        val id = clean.substring(0, 3).toInt(16)
        val data = ByteArray((clean.length - 3) / 2) { i ->
            clean.substring(3 + i * 2, 5 + i * 2).toInt(16).toByte()
        }
        return id to data
    }
}
