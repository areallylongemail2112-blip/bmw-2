package com.bmw.assistant.core.ecu.uds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class DoipTest {

    @Test
    fun routingActivationRequest_hasExpectedHeader() {
        val bytes = Doip.routingActivationRequest()
        assertEquals(Doip.PROTOCOL_VERSION, bytes[0].toInt() and 0xFF)
        assertEquals((Doip.PROTOCOL_VERSION.inv()) and 0xFF, bytes[1].toInt() and 0xFF)
        val type = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        assertEquals(Doip.TYPE_ROUTING_ACTIVATION_REQ, type)
    }

    @Test
    fun readFrame_roundTrip() {
        val uds = byteArrayOf(0x22, 0x30, 0x00)
        val frameBytes = Doip.diagnosticMessage(Doip.TESTER_ADDRESS, 0x72, uds)
        val frame = Doip.readFrame(ByteArrayInputStream(frameBytes))
        assertEquals(Doip.TYPE_DIAGNOSTIC_MESSAGE, frame.payloadType)
        // Request frames are sourced from the tester; don't enforce ECU address here.
        assertArrayEquals(uds, Doip.udsFromDiagnostic(frame.payload))
    }

    @Test
    fun readFrame_rejectsBadProtocolVersion() {
        val bad = byteArrayOf(
            0x01, 0xFE.toByte(), // wrong version
            0x00, 0x05,
            0x00, 0x00, 0x00, 0x00
        )
        assertThrows(IllegalArgumentException::class.java) {
            Doip.readFrame(ByteArrayInputStream(bad))
        }
    }

    @Test
    fun readFrame_rejectsHugePayloadLength() {
        val bad = byteArrayOf(
            Doip.PROTOCOL_VERSION.toByte(),
            (Doip.PROTOCOL_VERSION.inv()).toByte(),
            0x00, 0x05,
            0x10, 0x00, 0x00, 0x00 // 256MB
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Doip.readFrame(ByteArrayInputStream(bad))
        }
        assertTrueMessage(ex, "out of range")
    }

    @Test
    fun udsFromDiagnostic_checksSourceAddress() {
        val payload = byteArrayOf(
            0x00, 0x60, // source 0x60
            0x0E.toByte(), 0x80.toByte(), // target tester
            0x62, 0x30, 0x00, 0x01
        )
        assertThrows(IllegalArgumentException::class.java) {
            Doip.udsFromDiagnostic(payload, expectedTarget = 0x72)
        }
        val uds = Doip.udsFromDiagnostic(payload, expectedTarget = 0x60)
        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00, 0x01), uds)
    }

    private fun assertTrueMessage(ex: Throwable, fragment: String) {
        val msg = ex.message ?: ""
        if (!msg.contains(fragment)) {
            throw AssertionError("Expected message containing \"$fragment\", got \"$msg\"")
        }
    }
}
