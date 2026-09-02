package com.bmw.assistant.core.ecu.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISO-TP with BMW's extended addressing is what carries a coding block over an OBD dongle. The
 * two failure modes these tests exist for are a frame that is not padded to 8 bytes (F-series
 * modules drop those, so nothing works) and a reassembler that accepts a malformed or
 * out-of-order frame (which would hand truncated bytes to the coding engine).
 */
class IsoTpTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }

    @Test
    fun ecuCanId_isSixHundredPlusDiagnosticAddress() {
        assertEquals(0x672, IsoTp.ecuCanId(0x72))
        assertEquals(0x610, IsoTp.ecuCanId(0x10))
    }

    @Test
    fun singleFrame_isPaddedToEightBytes() {
        val frames = IsoTp.buildFrames(0x72, byteArrayOf(0x22, 0x30, 0x00))

        assertEquals(1, frames.size)
        assertEquals(IsoTp.FRAME_SIZE, frames[0].size)
        assertEquals("7203223000000000", hex(frames[0]))
    }

    @Test
    fun singleFrame_holdsAtMostSixPayloadBytes() {
        val six = ByteArray(6) { (it + 1).toByte() }
        assertEquals(1, IsoTp.buildFrames(0x72, six).size)
        assertEquals(2, IsoTp.buildFrames(0x72, six + byteArrayOf(7)).size)
    }

    @Test
    fun firstFrame_encodesTwelveBitLengthAndCarriesFiveBytes() {
        val payload = ByteArray(10) { (it + 1).toByte() }
        val frames = IsoTp.buildFrames(0x72, payload)

        assertEquals(2, frames.size)
        // 0x1 PCI nibble, length 10 split across the low nibble and the next byte.
        assertEquals("72100A0102030405", hex(frames[0]))
        // Consecutive frame 1 carries the remaining 5 bytes (0x06..0x0A), then padding.
        assertEquals("7221060708090A00", hex(frames[1]))
        frames.forEach { assertEquals(IsoTp.FRAME_SIZE, it.size) }
    }

    @Test
    fun firstFrame_encodesLengthAboveOneByte() {
        val frames = IsoTp.buildFrames(0x72, ByteArray(300))
        assertEquals("72", hex(frames[0]).substring(0, 2))
        // 300 = 0x12C → high nibble 1, low byte 0x2C.
        assertEquals("112C", hex(frames[0]).substring(2, 6))
    }

    @Test
    fun consecutiveFrames_wrapSequenceNumberAtSixteen() {
        // 5 bytes in the first frame + 16 consecutive frames of 6 = 101 bytes.
        val frames = IsoTp.buildFrames(0x72, ByteArray(101))

        assertEquals(17, frames.size)
        assertEquals(0x21, frames[1][1].toInt() and 0xFF)
        assertEquals(0x2F, frames[15][1].toInt() and 0xFF)
        // Sequence wraps 15 → 0, not 15 → 16.
        assertEquals(0x20, frames[16][1].toInt() and 0xFF)
    }

    @Test
    fun buildFrames_rejectsOversizeAndEmptyPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            IsoTp.buildFrames(0x72, ByteArray(IsoTp.MAX_PAYLOAD + 1))
        }
        assertThrows(IsoTpException::class.java) { IsoTp.buildFrames(0x72, ByteArray(0)) }
        // Exactly at the limit is allowed.
        IsoTp.buildFrames(0x72, ByteArray(IsoTp.MAX_PAYLOAD))
    }

    @Test
    fun flowControl_isPaddedAndCarriesBlockSizeAndSeparation() {
        assertEquals("7230000A00000000", hex(IsoTp.flowControl(0x72)))
        assertEquals("7230040500000000", hex(IsoTp.flowControl(0x72, blockSize = 4, stMinMs = 5)))
        assertThrows(IllegalArgumentException::class.java) { IsoTp.flowControl(0x72, stMinMs = 200) }
    }

    @Test
    fun flowControlFields_areDecoded() {
        val wait = byteArrayOf(0xF1.toByte(), 0x31, 0x08, 0x14)
        assertEquals(IsoTp.PCI_FLOW_CONTROL, IsoTp.pciType(wait))
        assertEquals(IsoTp.FS_WAIT, IsoTp.flowStatus(wait))
        assertEquals(8, IsoTp.flowControlBlockSize(wait))
        assertEquals(20L, IsoTp.flowControlStMinMs(wait))
    }

    @Test
    fun flowControlStMin_roundsMicrosecondValuesUpAndIgnoresReserved() {
        assertEquals(1L, IsoTp.flowControlStMinMs(byteArrayOf(0, 0x30, 0, 0xF5.toByte())))
        assertEquals(0L, IsoTp.flowControlStMinMs(byteArrayOf(0, 0x30, 0, 0x90.toByte())))
    }

    @Test
    fun reassembler_readsASingleFrame() {
        val assembler = IsoTp.Reassembler()
        val needsFlowControl = assembler.feed(
            byteArrayOf(0xF1.toByte(), 0x03, 0x62, 0x30, 0x00, 0, 0, 0)
        )

        assertFalse(needsFlowControl)
        assertTrue(assembler.isComplete)
        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00), assembler.payload)
    }

    @Test
    fun reassembler_readsASegmentedResponse() {
        val assembler = IsoTp.Reassembler()
        assertTrue(assembler.feed(byteArrayOf(0xF1.toByte(), 0x10, 0x0A, 1, 2, 3, 4, 5)))
        assertFalse(assembler.isComplete)

        assertFalse(assembler.feed(byteArrayOf(0xF1.toByte(), 0x21, 6, 7, 8, 9, 10, 0)))
        assertTrue(assembler.isComplete)
        // Padding past the announced length is trimmed off.
        assertArrayEquals(ByteArray(10) { (it + 1).toByte() }, assembler.payload)
    }

    @Test
    fun reassembler_rejectsOutOfOrderConsecutiveFrames() {
        val assembler = IsoTp.Reassembler()
        assembler.feed(byteArrayOf(0xF1.toByte(), 0x10, 0x0A, 1, 2, 3, 4, 5))

        val error = assertThrows(IsoTpException::class.java) {
            assembler.feed(byteArrayOf(0xF1.toByte(), 0x22, 6, 7, 8, 9, 10, 0))
        }
        assertTrue(error.message!!.contains("sequence"))
    }

    @Test
    fun reassembler_rejectsAConsecutiveFrameWithNoFirstFrame() {
        val assembler = IsoTp.Reassembler()

        val error = assertThrows(IsoTpException::class.java) {
            assembler.feed(byteArrayOf(0xF1.toByte(), 0x21, 1, 2, 3, 4, 5, 6))
        }
        assertTrue(error.message!!.contains("without a first frame"))
        // And it must not have produced a payload from nothing.
        assertFalse(assembler.isComplete)
        assertEquals(0, assembler.payload.size)
    }

    @Test
    fun reassembler_rejectsInvalidSingleAndFirstFrameLengths() {
        assertThrows(IsoTpException::class.java) {
            IsoTp.Reassembler().feed(byteArrayOf(0xF1.toByte(), 0x00, 1, 2, 3, 4, 5, 6))
        }
        assertThrows(IsoTpException::class.java) {
            IsoTp.Reassembler().feed(byteArrayOf(0xF1.toByte(), 0x07, 1, 2, 3, 4, 5, 6))
        }
        // A first frame must announce more than a single frame could carry.
        assertThrows(IsoTpException::class.java) {
            IsoTp.Reassembler().feed(byteArrayOf(0xF1.toByte(), 0x10, 0x04, 1, 2, 3, 4, 5))
        }
    }

    @Test
    fun reassembler_ignoresFlowControlFrames() {
        val assembler = IsoTp.Reassembler()
        assertFalse(assembler.feed(byteArrayOf(0xF1.toByte(), 0x30, 0x00, 0x00, 0, 0, 0, 0)))
        assertFalse(assembler.isComplete)
    }

    @Test
    fun isResponsePending_recognisesTheBusyNegativeResponse() {
        assertTrue(IsoTp.isResponsePending(byteArrayOf(0xF1.toByte(), 0x03, 0x7F, 0x22, 0x78, 0, 0, 0)))
        assertFalse(IsoTp.isResponsePending(byteArrayOf(0xF1.toByte(), 0x03, 0x7F, 0x22, 0x31, 0, 0, 0)))
        assertFalse(IsoTp.isResponsePending(byteArrayOf(0xF1.toByte(), 0x03, 0x62, 0x30, 0x00, 0, 0, 0)))
    }

    @Test
    fun parseElmLine_readsAnElevenBitFrame() {
        val frame = IsoTp.parseElmLine("672F1066230000102")!!

        assertEquals(0x672, frame.id)
        assertArrayEquals(
            byteArrayOf(0xF1.toByte(), 0x06, 0x62, 0x30, 0x00, 0x01, 0x02),
            frame.data
        )
    }

    @Test
    fun parseElmLine_toleratesSpacesFromAdaptersLeftInAtS1() {
        val frame = IsoTp.parseElmLine("672 F1 03 62 30 00")!!
        assertEquals(0x672, frame.id)
        assertArrayEquals(byteArrayOf(0xF1.toByte(), 0x03, 0x62, 0x30, 0x00), frame.data)
    }

    @Test
    fun parseElmLine_returnsNullForStatusLines() {
        listOf("OK", "NO DATA", "SEARCHING...", "CAN ERROR", "BUFFER FULL", ">", "", "?", "STOPPED")
            .forEach { assertNull("expected null for \"$it\"", IsoTp.parseElmLine(it)) }
    }

    @Test
    fun parseElmLine_rejectsOddDigitsAndOversizeFrames() {
        assertNull(IsoTp.parseElmLine("672F1062"))
        // 9 data bytes cannot come from a CAN frame.
        assertNull(IsoTp.parseElmLine("672" + "AA".repeat(9)))
    }
}
