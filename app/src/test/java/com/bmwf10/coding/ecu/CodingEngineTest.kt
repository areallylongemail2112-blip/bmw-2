package com.bmwf10.coding.ecu

import com.bmwf10.coding.data.model.CodingItem
import com.bmwf10.coding.data.model.EcuMap
import com.bmwf10.coding.data.model.Module
import com.bmwf10.coding.data.model.ValueType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CodingEngineTest {

    private val module = Module(
        id = "frm",
        name = "FRM",
        fullName = "Footwell Module",
        description = "Lighting controller",
        iconName = "zap",
        diagAddress = 0x72
    )

    @Test
    fun applyCoding_preservesBitsOutsideTheCodingMask() {
        val transport = FakeTransport(byteArrayOf(0xAA.toByte()))
        val coding = booleanCoding(verified = true)

        val writtenByte = CodingEngine(transport, isDemo = false)
            .applyCoding(module, coding, "true")

        assertEquals(0xA9.toByte(), writtenByte)
        assertArrayEquals(byteArrayOf(0xA9.toByte()), transport.written)
        assertEquals(0x72, transport.lastDiagAddress)
        assertEquals(0x3000, transport.lastDid)
    }

    @Test
    fun applyCoding_rejectsUnverifiedHardwareMapBeforeReading() {
        val transport = FakeTransport(byteArrayOf(0x00))
        val coding = booleanCoding(verified = false)

        assertThrows(EcuException::class.java) {
            CodingEngine(transport, isDemo = false).applyCoding(module, coding, "true")
        }

        assertFalse(transport.wasRead)
    }

    @Test
    fun applyCoding_allowsUnverifiedMapInDemoMode() {
        val transport = FakeTransport(byteArrayOf(0x00))
        val coding = booleanCoding(verified = false)

        CodingEngine(transport, isDemo = true).applyCoding(module, coding, "true")

        assertArrayEquals(byteArrayOf(0x01), transport.written)
    }

    @Test
    fun readCoding_decodesMaskedBooleanValue() {
        val transport = FakeTransport(byteArrayOf(0xFD.toByte()))

        val value = CodingEngine(transport, isDemo = false)
            .readCoding(module, booleanCoding(verified = true))

        assertEquals("true", value)
    }

    private fun booleanCoding(verified: Boolean) = CodingItem(
        id = "frm_test",
        moduleId = "frm",
        name = "Test coding",
        description = "Test",
        longDescription = "Test coding",
        valueType = ValueType.BOOLEAN,
        defaultValue = "false",
        safeDefault = "false",
        ecuMap = EcuMap(
            dataIdentifier = 0x3000,
            byteOffset = 0,
            bitMask = 0x03,
            encodedValues = mapOf("false" to "0x00", "true" to "0x01"),
            verified = verified
        )
    )

    private class FakeTransport(initial: ByteArray) : EcuTransport {
        private val block = initial.copyOf()
        var written: ByteArray? = null
        var lastDiagAddress: Int? = null
        var lastDid: Int? = null
        var wasRead = false

        override val isConnected = true
        override val supportsCoding = true

        override fun connect() = Unit
        override fun disconnect() = Unit

        override fun readCodingBlock(diagAddress: Int, did: Int): ByteArray {
            wasRead = true
            return block.copyOf()
        }

        override fun writeCodingBlock(diagAddress: Int, did: Int, data: ByteArray) {
            lastDiagAddress = diagAddress
            lastDid = did
            written = data.copyOf()
        }
    }
}
