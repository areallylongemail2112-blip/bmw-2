package com.bmw.assistant.core.ecu

/**
 * Abstraction over a physical link to the car. A transport is a dumb UDS pipe: it moves one
 * request to a module and returns that module's response. Everything protocol-aware (session
 * handling, coding read-modify-write, DTC parsing, live-value decoding) is built on top of
 * [transceive] by [UdsClient] and the coding/diagnostics engines.
 *
 * Concrete transports:
 *   - [DemoTransport]      fully offline simulation (no hardware) — supports coding + diagnostics
 *   - [EnetHsfzTransport]  real ENET over TCP 6801 (HSFZ) — what a 2012 F10 gateway speaks
 *   - [EnetDoipTransport]  real ENET/DoIP over TCP 13400 (G-series / late F-series gateways)
 *   - [com.bmw.assistant.core.ecu.obd.Elm327Transport]  ELM327/STN OBD dongles over Bluetooth
 *     Classic, BLE or WiFi, using BMW's extended-addressed ISO-TP on the D-CAN bus
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

    /** Short human-readable description of the link (shown in the connection bar). */
    val description: String get() = javaClass.simpleName

    /**
     * Largest UDS request this link can carry. ENET carries whole blocks; a plain ELM327 over
     * CAN can only send single-frame requests. Coding writes larger than this are refused
     * with a clear message instead of silently truncating.
     */
    val maxRequestLength: Int get() = 4095

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
