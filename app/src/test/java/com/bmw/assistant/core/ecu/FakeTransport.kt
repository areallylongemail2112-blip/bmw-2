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
    var requireSecurity: Boolean = false,
    var vin: String? = null,
    var iLevel: String? = null,
    val unreachableAddresses: MutableSet<Int> = mutableSetOf()
) : EcuTransport {

    override var isConnected: Boolean = true
    private val unlocked = HashSet<Int>()
    val testerPresentCount = java.util.concurrent.atomic.AtomicInteger(0)
    val lastRoutines = mutableListOf<Pair<Int, Int>>()
    var seed: ByteArray = byteArrayOf(0x11, 0x22, 0x33, 0x44)

    override fun connect() { isConnected = true }
    override fun disconnect() { isConnected = false }

    override fun transceive(diagAddress: Int, request: ByteArray): ByteArray {
        if (diagAddress in unreachableAddresses) {
            return byteArrayOf(Uds.NEGATIVE_RESPONSE.toByte(), request[0], 0x11)
        }
        val sid = request[0].toInt() and 0xFF
        return when (sid) {
            Uds.SID_DIAGNOSTIC_SESSION_CONTROL ->
                byteArrayOf((sid + 0x40).toByte(), request.getOrElse(1) { 0x03 })
            Uds.SID_TESTER_PRESENT -> {
                testerPresentCount.incrementAndGet()
                byteArrayOf((sid + 0x40).toByte(), 0x00)
            }
            Uds.SID_READ_DATA_BY_IDENTIFIER -> {
                val did = ((request[1].toInt() and 0xFF) shl 8) or (request[2].toInt() and 0xFF)
                val identity = when (did) {
                    Uds.DID_VIN -> vin?.toByteArray(Charsets.US_ASCII)
                    Uds.DID_I_LEVEL -> iLevel?.toByteArray(Charsets.US_ASCII)
                    else -> null
                }
                val data = identity
                    ?: liveBlocks[diagAddress to did]
                    ?: codingBlocks.getOrPut(diagAddress to did) { ByteArray(8) }
                byteArrayOf((sid + 0x40).toByte(), (did shr 8).toByte(), did.toByte()) + data
            }
            Uds.SID_WRITE_DATA_BY_IDENTIFIER -> {
                if (requireSecurity && diagAddress !in unlocked) {
                    return byteArrayOf(Uds.NEGATIVE_RESPONSE.toByte(), sid.toByte(), 0x33)
                }
                val did = ((request[1].toInt() and 0xFF) shl 8) or (request[2].toInt() and 0xFF)
                codingBlocks[diagAddress to did] = request.copyOfRange(3, request.size)
                byteArrayOf((sid + 0x40).toByte(), (did shr 8).toByte(), did.toByte())
            }
            Uds.SID_SECURITY_ACCESS -> {
                val level = request.getOrElse(1) { 0 }.toInt() and 0xFF
                when (level) {
                    Uds.SECURITY_REQUEST_SEED ->
                        byteArrayOf((sid + 0x40).toByte(), level.toByte()) + seed
                    Uds.SECURITY_SEND_KEY -> {
                        val key = if (request.size > 2) request.copyOfRange(2, request.size) else ByteArray(0)
                        val expected = XorSecurityKeyProvider.keyFor(
                            diagAddress, Uds.SECURITY_REQUEST_SEED, seed
                        )
                        if (!key.contentEquals(expected)) {
                            return byteArrayOf(Uds.NEGATIVE_RESPONSE.toByte(), sid.toByte(), 0x35)
                        }
                        unlocked.add(diagAddress)
                        byteArrayOf((sid + 0x40).toByte(), level.toByte())
                    }
                    else -> byteArrayOf(Uds.NEGATIVE_RESPONSE.toByte(), sid.toByte(), 0x12)
                }
            }
            Uds.SID_ROUTINE_CONTROL -> {
                val routineId = ((request[2].toInt() and 0xFF) shl 8) or (request[3].toInt() and 0xFF)
                lastRoutines.add(diagAddress to routineId)
                byteArrayOf((sid + 0x40).toByte(), request[1], request[2], request[3])
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
