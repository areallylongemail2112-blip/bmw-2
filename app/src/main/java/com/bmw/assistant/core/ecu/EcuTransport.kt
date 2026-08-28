package com.bmw.assistant.core.ecu

/**
 * Abstraction over a physical link to the car. A transport is a dumb UDS pipe: it moves one
 * request to a module and returns that module's response. Everything protocol-aware (session
 * handling, coding read-modify-write, DTC parsing, live-value decoding) is built on top of
 * [transceive] by [UdsClient] and the coding/diagnostics engines.
 *
 * Concrete transports:
 *   - [DemoTransport]      fully offline simulation (no hardware) — supports coding + diagnostics
 *   - [EnetDoipTransport]  real ENET/DoIP over TCP (the coding-capable path for F-series)
 *   - [com.bmw.assistant.core.ecu.ble.BleObdTransport]  BLE OBD dongle (connection/handshake)
 *
 * All calls are blocking and must be invoked off the main thread (the ViewModels use
 * coroutines on Dispatchers.IO).
 */
interface EcuTransport {

    val isConnected: Boolean

    /** Whether this transport can *write* coding data to a module (not just read). */
    val supportsCoding: Boolean

    /** Whether this transport can read faults / live data (UDS 0x19 / 0x22). */
    val supportsDiagnostics: Boolean

    /** Establish the link (open socket, routing activation, GATT connect, ...). */
    fun connect()

    fun disconnect()

    /**
     * Send one UDS [request] to the module at [diagAddress] and return its raw UDS response
     * (the service byte and everything after it, without any DoIP/transport framing).
     *
     * Implementations absorb transport-level acks and UDS "response pending" (0x78) and return
     * the final answer. They do NOT interpret the response — a negative response (0x7F ..) is
     * returned as-is for the caller to handle.
     *
     * @throws EcuException on a link error.
     */
    fun transceive(diagAddress: Int, request: ByteArray): ByteArray
}

/** Raised for any ECU-level failure (negative response, timeout, link down). */
class EcuException(message: String, cause: Throwable? = null) : Exception(message, cause)
