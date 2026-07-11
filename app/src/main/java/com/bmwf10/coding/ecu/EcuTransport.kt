package com.bmwf10.coding.ecu

/**
 * Abstraction over a physical link to the car. Concrete transports:
 *   - [DemoTransport]      fully offline simulation (no hardware)
 *   - [EnetDoipTransport]  real ENET/DoIP over TCP (the coding-capable path for F-series)
 *
 * All calls are blocking and must be invoked off the main thread (the ViewModels use
 * coroutines on Dispatchers.IO).
 */
interface EcuTransport {

    val isConnected: Boolean

    /** Whether this transport is capable of *writing* coding data (not just reading). */
    val supportsCoding: Boolean

    /** Establish the link (open socket, routing activation, GATT connect, ...). */
    fun connect()

    fun disconnect()

    /**
     * ReadDataByIdentifier: fetch the coding block [did] from module [diagAddress].
     * Returns the payload bytes (without the echoed SID/DID header).
     */
    fun readCodingBlock(diagAddress: Int, did: Int): ByteArray

    /**
     * WriteDataByIdentifier: write [data] to coding block [did] of module [diagAddress].
     * Throws [EcuException] on a negative response or link error.
     */
    fun writeCodingBlock(diagAddress: Int, did: Int, data: ByteArray)
}

/** Raised for any ECU-level failure (negative response, timeout, link down). */
class EcuException(message: String, cause: Throwable? = null) : Exception(message, cause)
