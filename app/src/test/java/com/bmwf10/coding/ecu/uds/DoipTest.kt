package com.bmwf10.coding.ecu.uds

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DoipTest {

    @Test
    fun diagnosticMessage_buildsHeaderAddressesAndPayload() {
        val message = Doip.diagnosticMessage(
            source = 0x0E80,
            target = 0x0072,
            uds = byteArrayOf(0x22, 0x30, 0x00)
        )

        assertArrayEquals(
            byteArrayOf(
                0x02, 0xFD.toByte(), 0x80.toByte(), 0x01,
                0x00, 0x00, 0x00, 0x07,
                0x0E, 0x80.toByte(), 0x00, 0x72,
                0x22, 0x30, 0x00
            ),
            message
        )
    }

    @Test
    fun readFrame_parsesValidFrame() {
        val bytes = byteArrayOf(
            0x02, 0xFD.toByte(), 0x80.toByte(), 0x01,
            0x00, 0x00, 0x00, 0x03,
            0x62, 0x30, 0x00
        )

        val frame = Doip.readFrame(ByteArrayInputStream(bytes))

        assertEquals(Doip.TYPE_DIAGNOSTIC_MESSAGE, frame.payloadType)
        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00), frame.payload)
    }

    @Test
    fun readFrame_rejectsInvalidVersionInverse() {
        val bytes = byteArrayOf(
            0x02, 0x02, 0x80.toByte(), 0x01,
            0x00, 0x00, 0x00, 0x00
        )

        assertThrows(IOException::class.java) {
            Doip.readFrame(ByteArrayInputStream(bytes))
        }
    }

    @Test
    fun readFrame_rejectsOversizedPayloadBeforeAllocating() {
        val bytes = byteArrayOf(
            0x02, 0xFD.toByte(), 0x80.toByte(), 0x01,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )

        assertThrows(IOException::class.java) {
            Doip.readFrame(ByteArrayInputStream(bytes))
        }
    }
}
