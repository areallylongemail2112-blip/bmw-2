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
}
