package com.bmw.assistant.core.ecu.uds

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HsfzTest {

    @Test
    fun diagnosticRequest_roundTrip() {
        val uds = byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())
        val bytes = Hsfz.diagnosticRequest(0x72, uds)
        val frame = Hsfz.readFrame(ByteArrayInputStream(bytes))
        assertEquals(Hsfz.CTRL_DIAGNOSTIC, frame.control)
        assertEquals(Hsfz.TESTER_ADDRESS, frame.source)
        assertEquals(0x72, frame.target)
        assertArrayEquals(uds, frame.uds)
    }

    @Test
    fun parse_identificationDatagram() {
        val ident = Hsfz.identificationRequest()
        val parsed = Hsfz.parse(ident)
        assertEquals(Hsfz.CTRL_VEHICLE_IDENT, parsed!!.control)
        assertTrue(parsed.data.isEmpty())
    }

    @Test
    fun vinFromIdentification_readsVinEquals() {
        val data = "DIAGADR10VIN=WBAFR7C52CC123456REST".toByteArray(Charsets.ISO_8859_1)
        assertEquals("WBAFR7C52CC123456", Hsfz.vinFromIdentification(data))
        assertNull(Hsfz.vinFromIdentification("no vin here".toByteArray()))
    }
}
