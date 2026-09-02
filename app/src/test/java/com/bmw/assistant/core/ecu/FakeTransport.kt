package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Uds

/**
 * In-memory transport for unit tests. Answers UDS requests from maps the test seeds, so both
 * [com.bmw.assistant.core.coding.CodingEngine] and
 * [com.bmw.assistant.core.diagnostics.DiagnosticsEngine] can be exercised without hardware.
 */
class FakeTransport(
    private val codingBlocks: MutableMap<Pair<Int, Int>, ByteArray> = mutableMapOf(),
    private val liveBlocks: MutableMap<Pair<Int, Int>, ByteArray> = mutableMapOf(),
    private val faults: MutableMap<Int, MutableList<ByteArray>> = mutableMapOf(),
    override val supportsCoding: Boolean = true,
    override val supportsDiagnostics: Boolean = true,
    override val maxRequestLength: Int = 4095
) : EcuTransport {

    override var isConnected: Boolean = true
    var resetCount: Int = 0
        private set

    override fun connect() { isConnected = true }
    override fun disconnect() { isConnected = false }

    override fun transceive(diagAddress: Int, request: ByteArray): ByteArray {
        val sid = request[0].toInt() and 0xFF
        return when (sid) {
            Uds.SID_DIAGNOSTIC_SESSION_CONTROL ->
                byteArrayOf((sid + 0x40).toByte(), request.getOrElse(1) { 0x03 })
            Uds.SID_ECU_RESET -> {
                resetCount++
                byteArrayOf((sid + 0x40).toByte(), request.getOrElse(1) { 0x03 })
            }
            Uds.SID_TESTER_PRESENT ->
                byteArrayOf((sid + 0x40).toByte(), 0x00)
            Uds.SID_READ_DATA_BY_IDENTIFIER -> {
                val did = ((request[1].toInt() and 0xFF) shl 8) or (request[2].toInt() and 0xFF)
                val data = liveBlocks[diagAddress to did]
                    ?: codingBlocks.getOrPut(diagAddress to did) { ByteArray(8) }
                byteArrayOf((sid + 0x40).toByte(), (did shr 8).toByte(), did.toByte()) + data
            }
            Uds.SID_WRITE_DATA_BY_IDENTIFIER -> {
                val did = ((request[1].toInt() and 0xFF) shl 8) or (request[2].toInt() and 0xFF)
                codingBlocks[diagAddress to did] = request.copyOfRange(3, request.size)
                byteArrayOf((sid + 0x40).toByte(), (did shr 8).toByte(), did.toByte())
            }
            Uds.SID_READ_DTC_INFORMATION -> {
                val header = byteArrayOf(
                    (sid + 0x40).toByte(), Uds.DTC_REPORT_BY_STATUS_MASK.toByte(), 0xFF.toByte()
                )
                faults[diagAddress].orEmpty().fold(header) { acc, rec -> acc + rec }
            }
            Uds.SID_CLEAR_DIAGNOSTIC_INFORMATION -> {
                faults.remove(diagAddress)
                byteArrayOf((sid + 0x40).toByte())
            }
            else -> byteArrayOf(Uds.NEGATIVE_RESPONSE.toByte(), sid.toByte(), 0x11)
        }
    }

    fun putCoding(diagAddress: Int, did: Int, data: ByteArray) {
        codingBlocks[diagAddress to did] = data.copyOf()
    }

    fun getCoding(diagAddress: Int, did: Int): ByteArray? = codingBlocks[diagAddress to did]?.copyOf()

    fun putLive(diagAddress: Int, did: Int, data: ByteArray) {
        liveBlocks[diagAddress to did] = data.copyOf()
    }

    fun putFault(diagAddress: Int, dtc: ByteArray, status: Int) {
        faults.getOrPut(diagAddress) { mutableListOf() }.add(dtc + byteArrayOf(status.toByte()))
    }
}
