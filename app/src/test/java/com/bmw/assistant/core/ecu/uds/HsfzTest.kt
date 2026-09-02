package com.bmw.assistant.core.ecu.uds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HSFZ is the framing a 2012 F10 gateway actually speaks, and every byte of it goes to a car's
 * control modules. These tests pin the frame layout: a length field that is off by one, or a
 * control word compared with the wrong threshold, would put malformed frames on the wire.
 */
class HsfzTest {

    @Test
    fun diagnosticRequest_lengthCountsAddressBytesAndPayload() {
        val uds = byteArrayOf(0x22, 0x30, 0x00)
        val frame = Hsfz.diagnosticRequest(target = 0x72, uds = uds)

        // 4-byte big-endian length = 2 address bytes + 3 UDS bytes.
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x05), frame.copyOfRange(0, 4))
        // Control word 0x0001 = diagnostic.
        assertArrayEquals(byteArrayOf(0x00, 0x01), frame.copyOfRange(4, 6))
        // Source is the tester (0xF4), target the module.
        assertEquals(0xF4, frame[6].toInt() and 0xFF)
        assertEquals(0x72, frame[7].toInt() and 0xFF)
        assertArrayEquals(uds, frame.copyOfRange(8, frame.size))
        assertEquals(Hsfz.HEADER_SIZE + 5, frame.size)
    }

    @Test
    fun parseFrame_roundTripsARequest() {
        val uds = byteArrayOf(0x22, 0x30, 0x00)
        val parsed = Hsfz.parseFrame(Hsfz.diagnosticRequest(0x72, uds))!!

        assertEquals(Hsfz.CTRL_DIAGNOSTIC, parsed.control)
        assertEquals(0xF4, parsed.source)
        assertEquals(0x72, parsed.target)
        assertArrayEquals(uds, parsed.uds)
    }

    @Test
    fun payloadLength_readsBigEndian() {
        val header = byteArrayOf(0x00, 0x00, 0x01, 0x02, 0x00, 0x01)
        assertEquals(258, Hsfz.payloadLength(header))
    }

    @Test
    fun parseFrame_rejectsTruncatedAndShortFrames() {
        // Announces 5 payload bytes but only carries 4.
        val truncated = byteArrayOf(0, 0, 0, 5, 0, 1, 0xF4.toByte(), 0x72, 0x22, 0x30)
        assertNull(Hsfz.parseFrame(truncated))
        assertNull(Hsfz.parseFrame(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun parseFrame_acceptsZeroLengthControlFrame() {
        val aliveCheck = byteArrayOf(0, 0, 0, 0, 0x00, 0x12)
        val parsed = Hsfz.parseFrame(aliveCheck)!!

        assertEquals(Hsfz.CTRL_ALIVE_CHECK, parsed.control)
        assertEquals(-1, parsed.source)
        assertEquals(0, parsed.uds.size)
    }

    @Test
    fun aliveCheckResponse_carriesTheTesterAddress() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x02, 0x00, 0x12, 0x00, 0xF4.toByte()),
            Hsfz.aliveCheckResponse()
        )
    }

    @Test
    fun identificationRequest_isSixBytes() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x11),
            Hsfz.identificationRequest()
        )
    }

    @Test
    fun isError_startsAtTheFirstErrorControlWord() {
        assertFalse(Hsfz.Frame(Hsfz.CTRL_DIAGNOSTIC, ByteArray(0)).isError)
        assertFalse(Hsfz.Frame(Hsfz.CTRL_STATUS_INQUIRY, ByteArray(0)).isError)
        assertTrue(Hsfz.Frame(Hsfz.CTRL_ERR_INCORRECT_TESTER_ADDRESS, ByteArray(0)).isError)
        assertTrue(Hsfz.Frame(Hsfz.CTRL_ERR_OUT_OF_MEMORY, ByteArray(0)).isError)
    }

    @Test
    fun describeError_namesEveryKnownGatewayError() {
        assertTrue(Hsfz.describeError(Hsfz.CTRL_ERR_INCORRECT_DEST_ADDRESS).contains("destination"))
        assertTrue(Hsfz.describeError(Hsfz.CTRL_ERR_MESSAGE_TOO_LARGE).contains("too large"))
        assertTrue(Hsfz.describeError(0x0099).contains("0099"))
    }

    @Test
    fun vinFromIdentification_pullsTheVinOutOfAnIdentString() {
        val body = "DIAGADR10DIAGADR10VIN=WBAFR9C50BC123456trailing".toByteArray(Charsets.ISO_8859_1)
        assertEquals("WBAFR9C50BC123456", Hsfz.vinFromIdentification(body))
    }

    @Test
    fun vinFromIdentification_returnsNullWhenTooShort() {
        assertNull(Hsfz.vinFromIdentification("VIN=SHORT".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun parse_readsAnIdentificationDatagram() {
        val body = "VIN=WBAFR9C50BC123456".toByteArray(Charsets.ISO_8859_1)
        val datagram = Hsfz.frame(Hsfz.CTRL_VEHICLE_IDENT, body)
        val parsed = Hsfz.parse(datagram)!!

        assertEquals(Hsfz.CTRL_VEHICLE_IDENT, parsed.control)
        assertEquals("WBAFR9C50BC123456", Hsfz.vinFromIdentification(parsed.data))
    }
}
