package com.bmw.assistant.core.ecu.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoTpTest {

    @Test
    fun ecuCanId_is600PlusAddress() {
        assertEquals(0x672, IsoTp.ecuCanId(0x72))
        assertEquals(0x640, IsoTp.ecuCanId(0x40))
    }

    @Test
    fun singleFrame_roundTrip() {
        val payload = byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte())
        val frames = IsoTp.buildFrames(0x40, payload)
        assertEquals(1, frames.size)
        assertEquals(0x40, frames[0][0].toInt() and 0xFF)
        val asm = IsoTp.Reassembler()
        // Responses use tester address 0xF1 as the extended address.
        val rx = frames[0].copyOf()
        rx[0] = IsoTp.TESTER_ADDRESS.toByte()
        assertFalse(asm.feed(rx))
        assertTrue(asm.isComplete)
        assertArrayEquals(payload, asm.payload)
    }

    @Test
    fun multiFrame_needsFlowControlThenReassembles() {
        val payload = ByteArray(20) { it.toByte() }
        val frames = IsoTp.buildFrames(0x72, payload)
        assertTrue(frames.size > 1)
        val asm = IsoTp.Reassembler()
        val first = frames[0].copyOf().also { it[0] = IsoTp.TESTER_ADDRESS.toByte() }
        assertTrue(asm.feed(first))
        assertFalse(asm.isComplete)
        for (i in 1 until frames.size) {
            val cf = frames[i].copyOf().also { it[0] = IsoTp.TESTER_ADDRESS.toByte() }
            asm.feed(cf)
        }
        assertTrue(asm.isComplete)
        assertArrayEquals(payload, asm.payload)
    }

    @Test
    fun parseElmLine_headersOnNoSpaces() {
        val parsed = IsoTp.parseElmLine("672F10662F1500F25F0")
        assertEquals(0x672, parsed!!.first)
        assertEquals(0xF1, parsed.second[0].toInt() and 0xFF)
        assertNull(IsoTp.parseElmLine("NO DATA"))
        assertNull(IsoTp.parseElmLine("OK"))
    }
}
