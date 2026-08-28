package com.bmw.assistant.core.diagnostics

import com.bmw.assistant.data.model.LiveParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveDecoderTest {

    private fun param(
        did: Int = 0x4610,
        byteOffset: Int = 0,
        byteLength: Int = 1,
        scale: Double = 1.0,
        offset: Double = 0.0
    ) = LiveParameter(
        id = "p",
        moduleId = "dme",
        name = "p",
        dataIdentifier = did,
        byteOffset = byteOffset,
        byteLength = byteLength,
        scale = scale,
        offset = offset
    )

    @Test
    fun decodesTwoByteBigEndianWithScale() {
        // RPM = raw / 4; 0x0CD0 = 3280 -> 820 rpm
        val value = LiveDecoder.decode(param(byteLength = 2, scale = 0.25), byteArrayOf(0x0C, 0xD0.toByte()))
        assertEquals(820.0, value!!, 0.0001)
    }

    @Test
    fun decodesSingleByteWithOffset() {
        // temperature = raw - 48; 0x89 = 137 -> 89
        val value = LiveDecoder.decode(param(offset = -48.0), byteArrayOf(0x89.toByte()))
        assertEquals(89.0, value!!, 0.0001)
    }

    @Test
    fun respectsByteOffsetWindow() {
        val value = LiveDecoder.decode(
            param(byteOffset = 2, byteLength = 1),
            byteArrayOf(0x00, 0x00, 0x2A, 0x00)
        )
        assertEquals(42.0, value!!, 0.0001)
    }

    @Test
    fun returnsNullWhenPayloadTooShort() {
        assertNull(LiveDecoder.decode(param(byteLength = 2), byteArrayOf(0x0C)))
    }
}
