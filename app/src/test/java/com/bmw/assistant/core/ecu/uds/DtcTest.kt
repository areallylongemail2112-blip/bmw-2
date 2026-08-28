package com.bmw.assistant.core.ecu.uds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcTest {

    @Test
    fun parseByStatusMask_extractsRecordsAndDropsPadding() {
        val response = byteArrayOf(
            0x59, 0x02, 0xFF.toByte(),       // header + status availability mask
            0x2C, 0x6A, 0x08, 0x09,          // DTC 1 (active + confirmed)
            0x29, 0xE3.toByte(), 0x00, 0x08, // DTC 2 (confirmed)
            0x00, 0x00, 0x00, 0x00           // padding, should be dropped
        )
        val dtcs = Dtc.parseByStatusMask(response)
        assertEquals(2, dtcs.size)
        assertEquals("2C6A08", dtcs[0].hexCode())
        assertEquals("29E300", dtcs[1].hexCode())
    }

    @Test
    fun rawDtc_derivesHexAndSaeCodes() {
        val dtc = RawDtc(byteArrayOf(0x2C, 0x6A, 0x08), 0x09)
        assertEquals("2C6A08", dtc.hexCode())
        assertEquals(0x2C6A, dtc.high16)
        // 0x2C -> letter bits 00 = 'P', digits 2 and C; 0x6A -> 6 and A
        assertEquals("P2C6A", dtc.saeCode())
    }

    @Test
    fun statusBits_mapToLabels() {
        val active = RawDtc(byteArrayOf(0x2C, 0x6A, 0x08), 0x09) // bit0 testFailed
        assertTrue(active.isTestFailed)
        assertEquals("Active", active.statusLabel())

        val stored = RawDtc(byteArrayOf(0x29, 0xE3.toByte(), 0x00), 0x08) // bit3 confirmed only
        assertFalse(stored.isTestFailed)
        assertTrue(stored.isConfirmed)
        assertEquals("Stored", stored.statusLabel())
    }

    @Test
    fun parseByStatusMask_shortResponseIsEmpty() {
        assertEquals(0, Dtc.parseByStatusMask(byteArrayOf(0x59, 0x02)).size)
    }
}
