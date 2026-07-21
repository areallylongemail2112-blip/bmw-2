package com.bmwf10.coding.ecu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {

    @Test fun decodeAcceptsCommonFormats() {
        assertArrayEquals(byteArrayOf(0x1A), Hex.decode("0x1A"))
        assertArrayEquals(byteArrayOf(0x1A), Hex.decode("1a"))
        assertArrayEquals(byteArrayOf(0x1A, 0x2B), Hex.decode("1A 2B"))
        assertArrayEquals(byteArrayOf(0x1A, 0x2B), Hex.decode("1A2B"))
        assertArrayEquals(byteArrayOf(0x1A, 0x2B), Hex.decode("1a,2b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsOddLength() {
        Hex.decode("1A2")
    }

    @Test fun encodeRoundTrips() {
        assertEquals("1A 2B FF", Hex.encode(byteArrayOf(0x1A, 0x2B, 0xFF.toByte())))
        assertEquals("1A2BFF", Hex.encodeCompact(byteArrayOf(0x1A, 0x2B, 0xFF.toByte())))
    }

    @Test fun parseByteMasksToOneByte() {
        assertEquals(0x01, Hex.parseByte("0x01"))
        assertEquals(0xFF, Hex.parseByte("ff"))
        assertEquals(0x00, Hex.parseByte("0x100"))
    }
}
