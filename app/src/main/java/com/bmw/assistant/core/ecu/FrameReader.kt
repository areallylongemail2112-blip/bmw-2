package com.bmw.assistant.core.ecu

import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * A length-prefixed frame reader that **does not lose bytes when a read times out**.
 *
 * `DataInputStream.readFully` consumes whatever has arrived and then throws, so a frame split
 * across TCP segments (routine with ENET-WiFi adapters, which are serial-to-TCP bridges) leaves
 * the stream mid-frame. Every later read then interprets payload bytes as a header, producing
 * bogus lengths, "length out of range" errors, or a 64 KB read that hangs until the socket dies.
 *
 * This reader accumulates into its own buffer and only ever *peeks* until a whole frame is
 * present, so a timeout is recoverable: the partial frame stays buffered and the next attempt
 * continues where it left off.
 */
class FrameReader(
    private val socket: Socket,
    private val input: InputStream,
    private val chunkSize: Int = 4096
) {
    private var buffer = ByteArray(0)
    private var start = 0

    /** Bytes already received but not yet consumed. */
    val buffered: Int get() = buffer.size - start

    /**
     * Ensures at least [count] bytes are buffered and returns them **without consuming**.
     * @throws SocketTimeoutException if [deadlineMs] passes first (buffered bytes are kept).
     * @throws EOFException if the peer closes the connection.
     */
    fun peek(count: Int, deadlineMs: Long): ByteArray {
        while (buffered < count) {
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) throw SocketTimeoutException("Timed out waiting for $count bytes")
            socket.soTimeout = remaining.coerceIn(1, POLL_MS.toLong()).toInt()
            val chunk = ByteArray(chunkSize)
            val read = try {
                input.read(chunk)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (read < 0) throw EOFException("Connection closed by the gateway")
            if (read > 0) append(chunk, read)
        }
        return buffer.copyOfRange(start, start + count)
    }

    /** Drops [count] already-peeked bytes. */
    fun consume(count: Int) {
        start += count
        if (start >= buffer.size) {
            buffer = ByteArray(0)
            start = 0
        } else if (start > COMPACT_THRESHOLD) {
            buffer = buffer.copyOfRange(start, buffer.size)
            start = 0
        }
    }

    /**
     * Throws away everything buffered plus anything that arrives during [quietMs] of continuous
     * silence, up to [maxMs] in total. Used after a timeout so a late answer to the *previous*
     * request can never be mistaken for the answer to the next one.
     */
    fun drain(quietMs: Int = DRAIN_QUIET_MS, maxMs: Int = DRAIN_MAX_MS) {
        buffer = ByteArray(0)
        start = 0
        val hardDeadline = System.currentTimeMillis() + maxMs
        val chunk = ByteArray(chunkSize)
        while (System.currentTimeMillis() < hardDeadline) {
            socket.soTimeout = quietMs
            val read = try {
                input.read(chunk)
            } catch (_: SocketTimeoutException) {
                return // quiet for a full window: the link is in sync again
            } catch (_: Exception) {
                return
            }
            if (read <= 0) return
        }
    }

    private fun append(chunk: ByteArray, length: Int) {
        if (start > 0 && start == buffer.size) {
            buffer = ByteArray(0)
            start = 0
        }
        val kept = buffer.copyOfRange(start, buffer.size)
        start = 0
        buffer = ByteArray(kept.size + length)
        System.arraycopy(kept, 0, buffer, 0, kept.size)
        System.arraycopy(chunk, 0, buffer, kept.size, length)
    }

    private companion object {
        const val POLL_MS = 500
        const val COMPACT_THRESHOLD = 8192
        const val DRAIN_QUIET_MS = 150
        const val DRAIN_MAX_MS = 2000
    }
}
