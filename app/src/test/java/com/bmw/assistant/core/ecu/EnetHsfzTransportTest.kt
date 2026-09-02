package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Hsfz
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * Drives the real HSFZ transport against a scripted gateway on loopback.
 *
 * The behaviour under test is the part that protects a coding session: an answer is only ever
 * returned to the request that asked for it. A stale frame handed to the next request would make
 * the coding engine read one block and write those bytes into a different one.
 */
class EnetHsfzTransportTest {

    private val zgw = FramedTcpTransport.ZGW_ADDRESS
    private val frm = 0x72
    private var gateway: FakeGateway? = null

    @After
    fun tearDown() {
        gateway?.close()
    }

    /** A gateway that replies to each diagnostic frame using [script]. */
    private inner class FakeGateway(
        private val script: (target: Int, uds: ByteArray, out: OutputStream) -> Unit
    ) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val received = Collections.synchronizedList(mutableListOf<Hsfz.Frame>())

        private val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val out = socket.getOutputStream()
                    while (!socket.isClosed) {
                        val frame = Hsfz.readFrame(input)
                        received += frame
                        if (frame.control != Hsfz.CTRL_DIAGNOSTIC) continue
                        script(frame.target, frame.uds, out)
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            runCatching { server.close() }
            thread.interrupt()
        }
    }

    private fun diagnostic(source: Int, uds: ByteArray): ByteArray =
        Hsfz.frame(
            Hsfz.CTRL_DIAGNOSTIC,
            byteArrayOf(source.toByte(), Hsfz.TESTER_ADDRESS.toByte()) + uds
        )

    private fun ack(target: Int, uds: ByteArray): ByteArray =
        Hsfz.frame(
            Hsfz.CTRL_ACK,
            byteArrayOf(Hsfz.TESTER_ADDRESS.toByte(), target.toByte()) + uds
        )

    private fun OutputStream.send(bytes: ByteArray) {
        write(bytes)
        flush()
    }

    /** Answers TesterPresent so the transport's connect handshake completes. */
    private fun OutputStream.answerTesterPresent(target: Int) = send(diagnostic(target, byteArrayOf(0x7E, 0x00)))

    private fun connect(script: (Int, ByteArray, OutputStream) -> Unit): EnetHsfzTransport {
        val g = FakeGateway(script)
        gateway = g
        val transport = EnetHsfzTransport("127.0.0.1", g.port, readTimeoutMs = 1500)
        transport.connect()
        return transport
    }

    @Test
    fun connect_handshakesWithTheGatewayAndReportsTheLink() {
        val transport = connect { target, uds, out ->
            if (uds[0].toInt() == 0x3E) out.answerTesterPresent(target)
        }

        assertTrue(transport.isConnected)
        assertTrue(transport.description.contains("HSFZ"))
        transport.disconnect()
        assertFalse(transport.isConnected)
    }

    @Test
    fun connect_failsWhenTheGatewayNeverAnswers() {
        val g = FakeGateway { _, _, _ -> /* silence */ }
        gateway = g
        val transport = EnetHsfzTransport("127.0.0.1", g.port, readTimeoutMs = 400)

        assertThrows(EcuException::class.java) { transport.connect() }
        assertFalse(transport.isConnected)
    }

    @Test
    fun transceive_skipsTheGatewayAcknowledgementBeforeTheAnswer() {
        val transport = connect { target, uds, out ->
            when {
                uds[0].toInt() == 0x3E -> out.answerTesterPresent(target)
                else -> {
                    out.send(ack(target, uds))
                    out.send(diagnostic(target, byteArrayOf(0x62, 0x30, 0x00, 0x11)))
                }
            }
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00, 0x11), response)
    }

    @Test
    fun transceive_skipsAStaleAnswerForADifferentService() {
        // The module answers a *previous* TesterPresent first. Returning that as the answer to
        // a ReadDataByIdentifier is the bug this guards against.
        val transport = connect { target, uds, out ->
            when {
                uds[0].toInt() == 0x3E -> out.answerTesterPresent(target)
                else -> {
                    out.send(diagnostic(target, byteArrayOf(0x7E, 0x00)))
                    out.send(diagnostic(target, byteArrayOf(0x62, 0x30, 0x00, 0xAB.toByte())))
                }
            }
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00, 0xAB.toByte()), response)
    }

    @Test
    fun transceive_skipsAnAnswerFromADifferentModule() {
        val transport = connect { target, uds, out ->
            when {
                uds[0].toInt() == 0x3E -> out.answerTesterPresent(target)
                else -> {
                    out.send(diagnostic(0x60, byteArrayOf(0x62, 0x30, 0x00, 0x01)))
                    out.send(diagnostic(target, byteArrayOf(0x62, 0x30, 0x00, 0x02)))
                }
            }
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00, 0x02), response)
    }

    @Test
    fun transceive_absorbsResponsePendingAndReturnsTheFinalAnswer() {
        val transport = connect { target, uds, out ->
            when {
                uds[0].toInt() == 0x3E -> out.answerTesterPresent(target)
                else -> {
                    out.send(diagnostic(target, byteArrayOf(0x7F, 0x22, 0x78)))
                    Thread.sleep(120)
                    out.send(diagnostic(target, byteArrayOf(0x62, 0x30, 0x00, 0x55)))
                }
            }
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00, 0x55), response)
    }

    @Test
    fun transceive_answersAGatewayAliveCheckInline() {
        val transport = connect { target, uds, out ->
            when {
                uds[0].toInt() == 0x3E -> out.answerTesterPresent(target)
                else -> {
                    out.send(Hsfz.frame(Hsfz.CTRL_ALIVE_CHECK, ByteArray(0)))
                    Thread.sleep(50)
                    out.send(diagnostic(target, byteArrayOf(0x62, 0x30, 0x00, 0x07)))
                }
            }
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        val aliveResponses = gateway!!.received.count { it.control == Hsfz.CTRL_ALIVE_CHECK }
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00, 0x07), response)
        assertEquals("gateway alive check went unanswered", 1, aliveResponses)
    }

    @Test
    fun transceive_returnsANegativeResponseToTheCaller() {
        val transport = connect { target, uds, out ->
            when {
                uds[0].toInt() == 0x3E -> out.answerTesterPresent(target)
                else -> out.send(diagnostic(target, byteArrayOf(0x7F, 0x22, 0x31)))
            }
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        // Negative responses are the caller's to interpret, not the transport's to throw on.
        assertArrayEquals(byteArrayOf(0x7F, 0x22, 0x31), response)
    }

    @Test
    fun transceive_reportsAGatewayRejection() {
        val transport = connect { target, uds, out ->
            when {
                uds[0].toInt() == 0x3E -> out.answerTesterPresent(target)
                else -> out.send(Hsfz.frame(Hsfz.CTRL_ERR_INCORRECT_DEST_ADDRESS, ByteArray(0)))
            }
        }

        val error = assertThrows(EcuException::class.java) {
            transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        }
        transport.disconnect()

        assertTrue(error.message!!.contains("destination"))
    }

    @Test
    fun transceive_timesOutWithAMessageNamingTheModule() {
        val transport = connect { target, uds, out ->
            if (uds[0].toInt() == 0x3E) out.answerTesterPresent(target)
        }

        val error = assertThrows(EcuException::class.java) {
            transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        }
        transport.disconnect()

        assertTrue(error.message!!.contains("0x72"))
    }

    @Test
    fun requestFrame_addressesTheModuleFromTheTester() {
        val transport = connect { target, uds, out ->
            if (uds[0].toInt() == 0x3E) out.answerTesterPresent(target)
        }
        runCatching { transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00)) }
        transport.disconnect()

        val request = gateway!!.received.first { it.uds.firstOrNull()?.toInt() == 0x22 }
        assertEquals(Hsfz.TESTER_ADDRESS, request.source)
        assertEquals(frm, request.target)
        assertArrayEquals(byteArrayOf(0x22, 0x30, 0x00), request.uds)
    }
}
