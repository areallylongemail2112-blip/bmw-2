package com.bmwf10.coding.ecu

/**
 * Fully offline simulation of a car. Keeps an in-memory map of coding blocks per module so
 * that reads return whatever was last written, and writes always "succeed". This is what
 * powers demo mode — the entire app is usable and testable with no hardware attached.
 */
class DemoTransport : EcuTransport {

    // key = (diagAddress shl 16) or did  ->  coding block bytes
    private val blocks = HashMap<Int, ByteArray>()
    private var connected = false

    override val isConnected: Boolean get() = connected
    override val supportsCoding: Boolean get() = true

    override fun connect() {
        // Simulate a short handshake delay so the UI shows a "connecting" state.
        Thread.sleep(700)
        connected = true
    }

    override fun disconnect() {
        connected = false
        blocks.clear()
    }

    private fun key(diagAddress: Int, did: Int) = (diagAddress shl 16) or (did and 0xFFFF)

    override fun readCodingBlock(diagAddress: Int, did: Int): ByteArray {
        check(connected) { "Demo transport not connected" }
        // Default a fresh block to 8 zero bytes; enough room for our illustrative offsets.
        return blocks.getOrPut(key(diagAddress, did)) { ByteArray(8) }.copyOf()
    }

    override fun writeCodingBlock(diagAddress: Int, did: Int, data: ByteArray) {
        check(connected) { "Demo transport not connected" }
        Thread.sleep(300)
        blocks[key(diagAddress, did)] = data.copyOf()
    }

    /**
     * Seeds a coding block byte so demo UI values match what [CodingEngine] would read back.
     * Safe to call only while connected.
     */
    fun seedByte(diagAddress: Int, did: Int, byteOffset: Int, maskedValue: Int, bitMask: Int) {
        check(connected) { "Demo transport not connected" }
        require(byteOffset >= 0) { "byteOffset must be >= 0" }
        val k = key(diagAddress, did)
        val block = blocks.getOrPut(k) { ByteArray(maxOf(8, byteOffset + 1)) }
        val working = if (byteOffset < block.size) block else block.copyOf(byteOffset + 1)
        val existing = working[byteOffset].toInt() and 0xFF
        working[byteOffset] = ((existing and bitMask.inv()) or (maskedValue and bitMask)).toByte()
        blocks[k] = working
    }
}
