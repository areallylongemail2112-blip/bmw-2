package com.bmw.assistant.core.ecu

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The reason this class exists: an ENET-WiFi adapter is a serial-to-TCP bridge, so a gateway
 * frame routinely arrives split across segments. A reader that consumes bytes and then throws on
 * timeout leaves the stream mid-frame forever after. These tests pin the recovery behaviour.
 */
class FrameReaderTest {

    private lateinit var server: ServerSocket
    private lateinit var client: Socket
    private lateinit var peer: Socket

    private fun connect() {
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        client = Socket(InetAddress.getByName("127.0.0.1"), server.localPort)
        peer = server.accept()
    }

    @After
    fun tearDown() {
        runCatching { client.close() }
        runCatching { peer.close() }
        runCatching { server.close() }
    }

    @Test
    fun peek_keepsPartialDataAcrossATimeout() {
        connect()
        val reader = FrameReader(client, client.getInputStream())
        peer.getOutputStream().apply { write(byteArrayOf(1, 2, 3)); flush() }

        // Not enough for a 6-byte header yet: this must time out, not consume.
        assertThrows(SocketTimeoutException::class.java) {
            reader.peek(6, System.currentTimeMillis() + 200)
        }
        assertEquals(3, reader.buffered)

        peer.getOutputStream().apply { write(byteArrayOf(4, 5, 6)); flush() }
        val header = reader.peek(6, System.currentTimeMillis() + 2000)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), header)
    }

    @Test
    fun peek_doesNotConsumeUntilConsumeIsCalled() {
        connect()
        val reader = FrameReader(client, client.getInputStream())
        peer.getOutputStream().apply { write(byteArrayOf(9, 8, 7, 6)); flush() }

        val deadline = System.currentTimeMillis() + 2000
        assertArrayEquals(byteArrayOf(9, 8), reader.peek(2, deadline))
        assertArrayEquals(byteArrayOf(9, 8), reader.peek(2, deadline))

        reader.consume(2)
        assertArrayEquals(byteArrayOf(7, 6), reader.peek(2, deadline))
        assertEquals(2, reader.buffered)
    }

    @Test
    fun peek_reassemblesAFrameDeliveredOneByteAtATime() {
        connect()
        val reader = FrameReader(client, client.getInputStream())
        val payload = ByteArray(64) { it.toByte() }
        Thread {
            val out = peer.getOutputStream()
            payload.forEach { out.write(byteArrayOf(it)); out.flush(); Thread.sleep(1) }
        }.apply { isDaemon = true }.start()

        val read = reader.peek(payload.size, System.currentTimeMillis() + 5000)

        assertArrayEquals(payload, read)
    }

    @Test
    fun drain_discardsEverythingBufferedAndInFlight() {
        connect()
        val reader = FrameReader(client, client.getInputStream())
        peer.getOutputStream().apply { write(ByteArray(32) { 0x55 }); flush() }
        reader.peek(4, System.currentTimeMillis() + 2000)

        reader.drain(quietMs = 100, maxMs = 1000)

        assertEquals(0, reader.buffered)
        // And the next read starts clean rather than mid-stream.
        peer.getOutputStream().apply { write(byteArrayOf(0x42)); flush() }
        assertArrayEquals(byteArrayOf(0x42), reader.peek(1, System.currentTimeMillis() + 2000))
    }

    @Test
    fun peek_reportsAClosedConnection() {
        connect()
        val reader = FrameReader(client, client.getInputStream())
        peer.close()

        assertThrows(EOFException::class.java) {
            reader.peek(4, System.currentTimeMillis() + 2000)
        }
    }
}
