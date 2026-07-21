package com.bmwf10.coding.ecu

import com.bmwf10.coding.data.model.CodingItem
import com.bmwf10.coding.data.model.EcuMap
import com.bmwf10.coding.data.model.EnumOption
import com.bmwf10.coding.data.model.Module
import com.bmwf10.coding.data.model.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory transport used to observe exactly what [CodingEngine] reads and writes. */
private class FakeTransport(
    override val supportsCoding: Boolean = true,
    initial: Map<Int, ByteArray> = emptyMap()
) : EcuTransport {
    val blocks = HashMap<Int, ByteArray>().apply { putAll(initial) }
    override val isConnected = true
    override fun connect() {}
    override fun disconnect() {}
    private fun key(addr: Int, did: Int) = (addr shl 16) or (did and 0xFFFF)
    override fun readCodingBlock(diagAddress: Int, did: Int): ByteArray =
        blocks.getOrPut(key(diagAddress, did)) { ByteArray(8) }.copyOf()
    override fun writeCodingBlock(diagAddress: Int, did: Int, data: ByteArray) {
        blocks[key(diagAddress, did)] = data.copyOf()
    }
}

class CodingEngineTest {

    private val module = Module("frm", "FRM", "Footwell Module", "", "zap", 114)

    private fun boolCoding(mask: Int = 1, offset: Int = 0) = CodingItem(
        id = "b", moduleId = "frm", name = "Bool", description = "", longDescription = "",
        valueType = ValueType.BOOLEAN, defaultValue = "false", safeDefault = "false",
        ecuMap = EcuMap(
            dataIdentifier = 0x3000, byteOffset = offset, bitMask = mask,
            encodedValues = mapOf("true" to "0x01", "false" to "0x00"), verified = true
        )
    )

    @Test fun booleanRoundTripThroughRealValue() {
        val t = FakeTransport()
        val engine = CodingEngine(t, isDemo = false)
        val coding = boolCoding()
        engine.applyCoding(module, coding, "true")
        assertEquals("true", engine.readCoding(module, coding))
        engine.applyCoding(module, coding, "false")
        assertEquals("false", engine.readCoding(module, coding))
    }

    @Test fun readModifyWritePreservesOtherBits() {
        // Byte 0 already has unrelated upper bits set; our feature owns only bit 0.
        val t = FakeTransport(initial = mapOf((114 shl 16) or 0x3000 to byteArrayOf(0xF0.toByte(), 0, 0, 0, 0, 0, 0, 0)))
        val engine = CodingEngine(t, isDemo = false)
        val written = engine.applyCoding(module, boolCoding(mask = 1), "true")
        assertEquals(0xF1, written.toInt() and 0xFF)
    }

    @Test fun enumEncodesAndDecodes() {
        val coding = CodingItem(
            id = "e", moduleId = "frm", name = "Color", description = "", longDescription = "",
            valueType = ValueType.ENUM, defaultValue = "red", safeDefault = "red",
            options = listOf(EnumOption("Red", "red"), EnumOption("Green", "green")),
            ecuMap = EcuMap(
                dataIdentifier = 0x3000, byteOffset = 3, bitMask = 0xFF,
                encodedValues = mapOf("red" to "0x03", "green" to "0x04"), verified = true
            )
        )
        val engine = CodingEngine(FakeTransport(), isDemo = false)
        engine.applyCoding(module, coding, "green")
        assertEquals("green", engine.readCoding(module, coding))
    }

    @Test fun integerAppliesScaleOnWriteAndRead() {
        val coding = CodingItem(
            id = "i", moduleId = "frm", name = "Warn", description = "", longDescription = "",
            valueType = ValueType.INTEGER, defaultValue = "0", safeDefault = "0",
            min = 0, max = 300,
            ecuMap = EcuMap(dataIdentifier = 0x3000, byteOffset = 3, bitMask = 0xFF, scale = 2.0, verified = true)
        )
        val t = FakeTransport()
        val engine = CodingEngine(t, isDemo = false)
        // 130 / 2.0 = 65 stored raw
        val raw = engine.applyCoding(module, coding, "130")
        assertEquals(65, raw.toInt() and 0xFF)
        assertEquals("130", engine.readCoding(module, coding))
    }

    @Test fun growsBlockWhenModuleReturnsShortBlock() {
        val t = FakeTransport(initial = mapOf((114 shl 16) or 0x3000 to byteArrayOf(0x00)))
        val engine = CodingEngine(t, isDemo = false)
        val coding = boolCoding(mask = 1, offset = 5)
        // Should not throw even though the block is shorter than byteOffset.
        engine.applyCoding(module, coding, "true")
        assertEquals("true", engine.readCoding(module, coding))
    }

    @Test fun unverifiedMapBlockedOnRealHardware() {
        val coding = boolCoding().copy(
            ecuMap = boolCoding().ecuMap!!.copy(verified = false)
        )
        val engine = CodingEngine(FakeTransport(), isDemo = false)
        val ex = assertThrows(EcuException::class.java) {
            engine.applyCoding(module, coding, "true")
        }
        assertTrue(ex.message!!.contains("not verified"))
    }

    @Test fun unverifiedMapAllowedInDemoMode() {
        val coding = boolCoding().copy(
            ecuMap = boolCoding().ecuMap!!.copy(verified = false)
        )
        val engine = CodingEngine(FakeTransport(), isDemo = true)
        engine.applyCoding(module, coding, "true")
        assertEquals("true", engine.readCoding(module, coding))
    }

    @Test fun transportThatCannotCodeIsRejected() {
        val engine = CodingEngine(FakeTransport(supportsCoding = false), isDemo = true)
        val ex = assertThrows(EcuException::class.java) {
            engine.applyCoding(module, boolCoding(), "true")
        }
        assertTrue(ex.message!!.contains("cannot write coding"))
    }
}
