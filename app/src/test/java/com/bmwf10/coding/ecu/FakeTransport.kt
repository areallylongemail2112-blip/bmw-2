package com.bmwf10.coding.ecu

/** In-memory transport for CodingEngine unit tests. */
class FakeTransport(
    private val blocks: MutableMap<Pair<Int, Int>, ByteArray> = mutableMapOf(),
    override val supportsCoding: Boolean = true
) : EcuTransport {

    override var isConnected: Boolean = true

    override fun connect() { isConnected = true }
    override fun disconnect() { isConnected = false }

    override fun readCodingBlock(diagAddress: Int, did: Int): ByteArray =
        blocks.getOrPut(diagAddress to did) { ByteArray(8) }.copyOf()

    override fun writeCodingBlock(diagAddress: Int, did: Int, data: ByteArray) {
        blocks[diagAddress to did] = data.copyOf()
    }

    fun put(diagAddress: Int, did: Int, data: ByteArray) {
        blocks[diagAddress to did] = data.copyOf()
    }

    fun get(diagAddress: Int, did: Int): ByteArray? = blocks[diagAddress to did]?.copyOf()
}
