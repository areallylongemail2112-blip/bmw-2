package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.net.LinkNetwork
import com.bmw.assistant.core.ecu.uds.Uds
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Shared machinery for the two length-prefixed TCP transports (HSFZ and DoIP). Both speak
 * "one framed request, then framed frames back until the module's UDS answer arrives", and both
 * need the same things done right:
 *
 *  - a [FrameReader] so a frame split across TCP segments survives a read timeout,
 *  - **request/response correlation**: only a frame whose UDS payload is the positive response
 *    to the request's service id (or a negative response naming it) is returned. Anything else
 *    is skipped. Without this, a late answer to an earlier request is handed to the next one —
 *    which in a coding session means read-modify-write of the *wrong* block,
 *  - a drain after any timeout, so that stale answer is discarded rather than queued,
 *  - "response pending" (0x7F xx 0x78) absorbed with an extended deadline,
 *  - one lock shared with the TesterPresent pump so nothing interleaves on the socket.
 *
 * Subclasses supply only the framing: [headerSize], [payloadLength], [requestFrame] and
 * [classify].
 */
abstract class FramedTcpTransport(
    protected val host: String,
    protected val port: Int,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val pendingTimeoutMs: Int = DEFAULT_PENDING_TIMEOUT_MS
) : EcuTransport {

    /** How a received frame relates to the request in flight. */
    protected sealed class Incoming {
        /** A diagnostic response from [source] carrying [uds]. */
        data class Response(val source: Int, val uds: ByteArray) : Incoming()

        /** Transport-level noise (ack, alive check already answered, other module). */
        object Ignore : Incoming()

        /** The gateway refused the request; [reason] is shown to the user. */
        data class Rejected(val reason: String) : Incoming()
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var reader: FrameReader? = null
    /** Set when a timeout leaves a possibly-unread answer on the link. */
    @Volatile private var dirty = false

    protected val lock = Any()
    private val keepAlive = TesterPresentKeepAlive { addr, req -> rawTransceive(addr, req) }

    override val isConnected: Boolean
        get() {
            val s = socket ?: return false
            return s.isConnected && !s.isClosed
        }

    // --- subclass hooks ---

    /** Fixed number of leading bytes that announce the frame length. */
    protected abstract val headerSize: Int

    /** Largest payload accepted, so a corrupt length cannot force a huge allocation. */
    protected abstract val maxPayloadLength: Int

    /** Payload length announced by [header] (which is exactly [headerSize] bytes). */
    protected abstract fun payloadLength(header: ByteArray): Int

    /** The bytes to put on the wire for one UDS request. */
    protected abstract fun requestFrame(diagAddress: Int, uds: ByteArray): ByteArray

    /**
     * Classifies one complete frame (header included). Implementations may call [write] to
     * answer a gateway alive check inline.
     */
    protected abstract fun classify(frame: ByteArray): Incoming

    /** Runs after the socket is up; should prove the gateway is talking. */
    protected abstract fun handshake()

    /** Diagnostic address of the central gateway, kept alive for the whole session. */
    protected open val gatewayAddress: Int = ZGW_ADDRESS

    /** Prefix for connection-failure messages. */
    protected abstract val connectHint: String

    // --- lifecycle ---

    override fun connect() {
        val s = Socket()
        try {
            LinkNetwork.bind(s)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.soTimeout = readTimeoutMs
            val inp: InputStream = s.getInputStream()
            socket = s
            output = s.getOutputStream()
            reader = FrameReader(s, inp)
            dirty = false

            handshake()

            // Keep the gateway's own session alive for as long as we hold the socket: a ZGW
            // closes an idle connection, and the user may sit on a confirmation dialog for
            // minutes between reading a coding block and writing it back.
            keepAlive.pin(gatewayAddress)
            keepAlive.start()
        } catch (e: Exception) {
            runCatching { s.close() }
            socket = null; output = null; reader = null
            if (e is EcuException) throw e
            throw EcuException("$connectHint ${describe(e)}", e)
        }
    }

    override fun disconnect() {
        keepAlive.stop()
        synchronized(lock) {
            runCatching { socket?.close() }
            socket = null; output = null; reader = null
        }
    }

    override fun transceive(diagAddress: Int, request: ByteArray): ByteArray {
        keepAlive.touch(diagAddress)
        return rawTransceive(diagAddress, request)
    }

    private fun rawTransceive(diagAddress: Int, request: ByteArray): ByteArray = synchronized(lock) {
        try {
            transceiveLocked(diagAddress, request)
        } catch (e: EcuException) {
            throw e
        } catch (e: Exception) {
            // A framing or IO error means the stream position is no longer trustworthy.
            dropLink()
            throw EcuException("Link error on $description: ${describe(e)}", e)
        }
    }

    private fun transceiveLocked(diagAddress: Int, request: ByteArray): ByteArray {
        val out = output ?: throw EcuException("Not connected")
        val rdr = reader ?: throw EcuException("Not connected")
        if (!isConnected) throw EcuException("Not connected to $host:$port")

        // A previous request timed out: anything still on the link belongs to it.
        if (dirty) {
            rdr.drain()
            dirty = false
        }

        val requestSid = request.firstOrNull()?.toInt()?.and(0xFF) ?: throw EcuException("Empty UDS request")
        out.write(requestFrame(diagAddress, request))
        out.flush()

        var deadline = System.currentTimeMillis() + readTimeoutMs
        var pendingUntil = 0L
        var skipped = 0
        while (true) {
            val frame = try {
                readFrame(rdr, deadline)
            } catch (e: SocketTimeoutException) {
                if (pendingUntil != 0L && System.currentTimeMillis() < pendingUntil) {
                    deadline = System.currentTimeMillis() + readTimeoutMs
                    continue
                }
                dirty = true
                throw EcuException(
                    "No response from module 0x%02X within %d ms".format(diagAddress, readTimeoutMs), e
                )
            }
            when (val incoming = classify(frame)) {
                is Incoming.Rejected -> throw EcuException(incoming.reason)
                is Incoming.Ignore -> {
                    if (++skipped > MAX_SKIPPED_FRAMES) {
                        dropLink()
                        throw EcuException("Gateway stream out of sync — reconnect and try again")
                    }
                }
                is Incoming.Response -> {
                    if (incoming.source != diagAddress) {
                        // A late answer to an earlier request to another module.
                        if (++skipped > MAX_SKIPPED_FRAMES) {
                            dropLink()
                            throw EcuException("Gateway stream out of sync — reconnect and try again")
                        }
                        continue
                    }
                    val uds = incoming.uds
                    if (!matchesRequest(uds, requestSid)) {
                        // Right module, wrong service: a stale response. Never hand this to the
                        // caller — a coding read-modify-write would use the wrong block.
                        if (++skipped > MAX_SKIPPED_FRAMES) {
                            dropLink()
                            throw EcuException("Gateway stream out of sync — reconnect and try again")
                        }
                        continue
                    }
                    if (Uds.negativeResponseCode(uds) == NRC_RESPONSE_PENDING) {
                        if (pendingUntil == 0L) pendingUntil = System.currentTimeMillis() + pendingTimeoutMs
                        if (System.currentTimeMillis() > pendingUntil) {
                            dirty = true
                            throw EcuException("Module 0x%02X stayed busy for too long".format(diagAddress))
                        }
                        deadline = System.currentTimeMillis() + readTimeoutMs
                        continue
                    }
                    return uds
                }
            }
        }
    }

    /** Reads one whole frame, keeping partial data buffered if the deadline passes first. */
    private fun readFrame(rdr: FrameReader, deadline: Long): ByteArray {
        val header = rdr.peek(headerSize, deadline)
        val length = payloadLength(header)
        if (length < 0 || length > maxPayloadLength) {
            dropLink()
            throw EcuException("Malformed frame from the gateway (length $length) — reconnect and try again")
        }
        val frame = rdr.peek(headerSize + length, deadline)
        rdr.consume(headerSize + length)
        return frame
    }

    /** True when [uds] is the positive or negative response to service [requestSid]. */
    private fun matchesRequest(uds: ByteArray, requestSid: Int): Boolean {
        if (uds.isEmpty()) return false
        val first = uds[0].toInt() and 0xFF
        if (first == requestSid + Uds.POSITIVE_RESPONSE_OFFSET) return true
        return first == Uds.NEGATIVE_RESPONSE && uds.size >= 2 && (uds[1].toInt() and 0xFF) == requestSid
    }

    /**
     * Reads one complete frame during [handshake], before the request/response loop is running.
     * @throws SocketTimeoutException when [deadlineMs] passes.
     */
    protected fun readHandshakeFrame(deadlineMs: Long): ByteArray {
        val rdr = reader ?: throw EcuException("Not connected")
        return readFrame(rdr, deadlineMs)
    }

    /** Writes raw bytes to the gateway — used by [classify] to answer alive checks. */
    protected fun write(bytes: ByteArray) {
        val out = output ?: throw EcuException("Not connected")
        out.write(bytes)
        out.flush()
    }

    /** Closes the socket so [isConnected] stops lying after an unrecoverable framing error. */
    private fun dropLink() {
        runCatching { socket?.close() }
        socket = null; output = null; reader = null
        dirty = false
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "the gateway did not answer in time."
        is java.net.NoRouteToHostException -> "no route to $host — check the cable or WiFi network."
        is java.net.ConnectException -> "$host:$port refused the connection."
        is java.io.EOFException -> "the gateway closed the connection."
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        /** Central gateway (ZGW) diagnostic address on F-series cars. */
        const val ZGW_ADDRESS = 0x10
        const val NRC_RESPONSE_PENDING = 0x78

        const val DEFAULT_CONNECT_TIMEOUT_MS = 4000
        const val DEFAULT_READ_TIMEOUT_MS = 5000
        const val DEFAULT_PENDING_TIMEOUT_MS = 30_000

        private const val MAX_SKIPPED_FRAMES = 32
    }
}
