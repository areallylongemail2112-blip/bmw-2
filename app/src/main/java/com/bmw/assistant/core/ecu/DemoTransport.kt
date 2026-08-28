package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Uds
import kotlin.random.Random

/**
 * Fully offline simulation of a car. It answers UDS requests from in-memory state so the whole
 * app — coding *and* diagnostics — is usable and testable with no hardware attached:
 *
 *  - coding blocks (0x22 / 0x2E) read back exactly what was written,
 *  - live-data blocks (0x22) wander slightly on each read so gauges look alive,
 *  - fault memory (0x19 / 0x14) starts with a few seeded DTCs that Clear erases.
 */
class DemoTransport : EcuTransport {

    // key = (diagAddress shl 16) or did
    private val codingBlocks = HashMap<Int, ByteArray>()
    private val liveBlocks = HashMap<Int, ByteArray>()
    private val faults = HashMap<Int, MutableList<ByteArray>>() // diagAddress -> [dtc(3)+status(1)]*
    private var connected = false

    override val isConnected: Boolean get() = connected
    override val supportsCoding: Boolean get() = true
    override val supportsDiagnostics: Boolean get() = true

    override fun connect() {
        // Simulate a short handshake delay so the UI shows a "connecting" state.
        Thread.sleep(700)
        connected = true
    }

    override fun disconnect() {
        connected = false
        codingBlocks.clear()
        liveBlocks.clear()
        faults.clear()
    }

    private fun key(diagAddress: Int, did: Int) = (diagAddress shl 16) or (did and 0xFFFF)

    override fun transceive(diagAddress: Int, request: ByteArray): ByteArray {
        check(connected) { "Demo transport not connected" }
        if (request.isEmpty()) return negative(0x00, 0x13)
        return when (val sid = request[0].toInt() and 0xFF) {
            Uds.SID_DIAGNOSTIC_SESSION_CONTROL ->
                byteArrayOf((sid + 0x40).toByte(), request.getOrElse(1) { 0x03 })
            Uds.SID_TESTER_PRESENT ->
                byteArrayOf((sid + 0x40).toByte(), 0x00)
            Uds.SID_READ_DATA_BY_IDENTIFIER -> readDid(diagAddress, request)
            Uds.SID_WRITE_DATA_BY_IDENTIFIER -> writeDid(diagAddress, request)
            Uds.SID_READ_DTC_INFORMATION -> readDtc(diagAddress, request)
            Uds.SID_CLEAR_DIAGNOSTIC_INFORMATION -> {
                faults.remove(diagAddress)
                byteArrayOf((sid + 0x40).toByte())
            }
            else -> negative(sid, 0x11) // service not supported
        }
    }

    private fun readDid(diagAddress: Int, request: ByteArray): ByteArray {
        if (request.size < 3) return negative(Uds.SID_READ_DATA_BY_IDENTIFIER, 0x13)
        val did = ((request[1].toInt() and 0xFF) shl 8) or (request[2].toInt() and 0xFF)
        val k = key(diagAddress, did)
        val data = when {
            liveBlocks.containsKey(k) -> jitter(liveBlocks.getValue(k))
            else -> codingBlocks.getOrPut(k) { ByteArray(8) }
        }
        return byteArrayOf(
            (Uds.SID_READ_DATA_BY_IDENTIFIER + 0x40).toByte(),
            (did shr 8).toByte(), did.toByte()
        ) + data
    }

    private fun writeDid(diagAddress: Int, request: ByteArray): ByteArray {
        if (request.size < 3) return negative(Uds.SID_WRITE_DATA_BY_IDENTIFIER, 0x13)
        val did = ((request[1].toInt() and 0xFF) shl 8) or (request[2].toInt() and 0xFF)
        Thread.sleep(200)
        codingBlocks[key(diagAddress, did)] = request.copyOfRange(3, request.size)
        return byteArrayOf(
            (Uds.SID_WRITE_DATA_BY_IDENTIFIER + 0x40).toByte(),
            (did shr 8).toByte(), did.toByte()
        )
    }

    private fun readDtc(diagAddress: Int, request: ByteArray): ByteArray {
        val sub = request.getOrElse(1) { 0x02 }.toInt() and 0xFF
        if (sub != Uds.DTC_REPORT_BY_STATUS_MASK) return negative(Uds.SID_READ_DTC_INFORMATION, 0x12)
        val records = faults[diagAddress].orEmpty()
        val header = byteArrayOf(
            (Uds.SID_READ_DTC_INFORMATION + 0x40).toByte(),
            Uds.DTC_REPORT_BY_STATUS_MASK.toByte(),
            0xFF.toByte() // status availability mask
        )
        return records.fold(header) { acc, rec -> acc + rec }
    }

    private fun negative(sid: Int, nrc: Int): ByteArray =
        byteArrayOf(Uds.NEGATIVE_RESPONSE.toByte(), sid.toByte(), nrc.toByte())

    /**
     * Nudges the last byte of a live block by ±1 (clamped) around its seeded value on each read,
     * so a single-byte demo gauge visibly moves. It reads relative to the seed each time (no
     * persisted drift); multi-byte values move only in their low byte, which is intentional.
     */
    private fun jitter(block: ByteArray): ByteArray {
        if (block.isEmpty()) return block
        val out = block.copyOf()
        val last = out.size - 1
        val delta = Random.nextInt(-1, 2)
        out[last] = ((out[last].toInt() and 0xFF) + delta).coerceIn(0, 255).toByte()
        return out
    }

    // --- demo seeding (called by ConnectionManager on entering demo mode) ---

    /**
     * Seeds a coding-block byte so demo UI values match what the coding engine reads back.
     * Safe to call only while connected.
     */
    fun seedCodingByte(diagAddress: Int, did: Int, byteOffset: Int, maskedValue: Int, bitMask: Int) {
        check(connected) { "Demo transport not connected" }
        require(byteOffset >= 0) { "byteOffset must be >= 0" }
        val k = key(diagAddress, did)
        val block = codingBlocks.getOrPut(k) { ByteArray(maxOf(8, byteOffset + 1)) }
        val working = if (byteOffset < block.size) block else block.copyOf(byteOffset + 1)
        val existing = working[byteOffset].toInt() and 0xFF
        working[byteOffset] = ((existing and bitMask.inv()) or (maskedValue and bitMask)).toByte()
        codingBlocks[k] = working
    }

    /** Seeds a live-data block (read back with jitter) from raw bytes. */
    fun seedLiveBlock(diagAddress: Int, did: Int, raw: ByteArray) {
        check(connected) { "Demo transport not connected" }
        liveBlocks[key(diagAddress, did)] = raw.copyOf()
    }

    /** Seeds a stored fault (3-byte DTC + 1-byte status) for a module's demo fault memory. */
    fun seedFault(diagAddress: Int, dtc: ByteArray, status: Int) {
        check(connected) { "Demo transport not connected" }
        require(dtc.size == 3) { "A DTC is 3 bytes" }
        faults.getOrPut(diagAddress) { mutableListOf() }.add(dtc + byteArrayOf(status.toByte()))
    }
}
