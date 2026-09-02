package com.bmw.assistant.core.ecu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last line of defence against a response being paired with the wrong request: a 0x62/0x6E
 * reply echoes the identifier it refers to, and the client must refuse one that does not match.
 */
class UdsClientTest {

    /** A transport that answers with whatever [reply] produces, ignoring correlation. */
    private class ScriptedTransport(val reply: (ByteArray) -> ByteArray) : EcuTransport {
        override val isConnected = true
        override val supportsCoding = true
        override val supportsDiagnostics = true
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun transceive(diagAddress: Int, request: ByteArray): ByteArray = reply(request)
    }

    private fun sessionOr(request: ByteArray, otherwise: () -> ByteArray): ByteArray =
        if ((request[0].toInt() and 0xFF) == 0x10) byteArrayOf(0x50, 0x03) else otherwise()

    @Test
    fun readDataByIdentifier_stripsTheEchoedHeader() {
        val client = UdsClient(ScriptedTransport { request ->
            sessionOr(request) { byteArrayOf(0x62, 0x30, 0x00, 0x0A, 0x0B) }
        })

        assertArrayEquals(byteArrayOf(0x0A, 0x0B), client.readDataByIdentifier(0x72, 0x3000))
    }

    @Test
    fun readDataByIdentifier_rejectsAnAnswerForADifferentIdentifier() {
        val client = UdsClient(ScriptedTransport { request ->
            // Positive response, right service, wrong identifier: a stale answer.
            sessionOr(request) { byteArrayOf(0x62, 0x30, 0x01, 0x0A, 0x0B) }
        })

        val error = assertThrows(EcuException::class.java) {
            client.readDataByIdentifier(0x72, 0x3000)
        }
        assertTrue(error.message!!.contains("mismatch"))
    }

    @Test
    fun writeDataByIdentifier_rejectsAnAnswerForADifferentIdentifier() {
        val client = UdsClient(ScriptedTransport { request ->
            sessionOr(request) { byteArrayOf(0x6E, 0x30, 0x99.toByte()) }
        })

        val error = assertThrows(EcuException::class.java) {
            client.writeDataByIdentifier(0x72, 0x3000, byteArrayOf(0x01))
        }
        assertTrue(error.message!!.contains("mismatch"))
    }

    @Test
    fun readDataByIdentifier_reportsANegativeResponseInPlainWords() {
        val client = UdsClient(ScriptedTransport { request ->
            sessionOr(request) { byteArrayOf(0x7F, 0x22, 0x33) }
        })

        val error = assertThrows(EcuException::class.java) {
            client.readDataByIdentifier(0x72, 0x3000)
        }
        assertTrue(error.message!!.contains("Security access denied"))
    }

    @Test
    fun openExtendedSession_failsLoudlyWhenRefused() {
        val client = UdsClient(ScriptedTransport { byteArrayOf(0x7F, 0x10, 0x22) })

        val error = assertThrows(EcuException::class.java) { client.openExtendedSession(0x72) }
        assertTrue(error.message!!.contains("Conditions not correct"))
    }
}
