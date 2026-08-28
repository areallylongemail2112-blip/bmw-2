package com.bmw.assistant.core.coding

import com.bmw.assistant.core.ecu.EcuException
import com.bmw.assistant.core.ecu.FakeTransport
import com.bmw.assistant.data.model.CodingItem
import com.bmw.assistant.data.model.EcuMap
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.data.model.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingEngineTest {

    private val module = Module(
        id = "frm",
        name = "FRM",
        fullName = "Footwell Module",
        description = "test",
        iconName = "zap",
        diagAddress = 0x72
    )

    private fun coding(
        type: ValueType,
        map: EcuMap,
        defaultValue: String = "0",
        hexLength: Int? = null
    ) = CodingItem(
        id = "test",
        moduleId = "frm",
        name = "Test",
        description = "d",
        longDescription = "ld",
        valueType = type,
        defaultValue = defaultValue,
        safeDefault = defaultValue,
        hexLength = hexLength,
        ecuMap = map
    )

    @Test
    fun integerBitfield_encodesWithShift() {
        val transport = FakeTransport()
        transport.putCoding(0x72, 0x3000, byteArrayOf(0x0F, 0, 0, 0, 0, 0, 0, 0))
        val engine = CodingEngine(transport, isDemo = true)
        val item = coding(
            ValueType.INTEGER,
            EcuMap(dataIdentifier = 0x3000, byteOffset = 0, bitMask = 0xF0, scale = 1.0, verified = true),
            defaultValue = "0"
        )

        engine.applyCoding(module, item, "5")

        val block = transport.getCoding(0x72, 0x3000)!!
        // low nibble preserved (0x0F), high nibble becomes 5 -> 0x5F
        assertEquals(0x5F, block[0].toInt() and 0xFF)
        assertEquals("5", engine.readCoding(module, item))
    }

    @Test
    fun boolean_preservesOtherBits() {
        val transport = FakeTransport()
        transport.putCoding(0x72, 0x3000, byteArrayOf(0xFE.toByte(), 0, 0, 0, 0, 0, 0, 0))
        val engine = CodingEngine(transport, isDemo = true)
        val item = coding(
            ValueType.BOOLEAN,
            EcuMap(
                dataIdentifier = 0x3000,
                byteOffset = 0,
                bitMask = 0x01,
                encodedValues = mapOf("true" to "0x01", "false" to "0x00"),
                verified = true
            ),
            defaultValue = "false"
        )

        engine.applyCoding(module, item, "true")
        assertEquals(0xFF, transport.getCoding(0x72, 0x3000)!![0].toInt() and 0xFF)
        assertEquals("true", engine.readCoding(module, item))
    }

    @Test
    fun unverifiedMap_blockedOnHardware() {
        val engine = CodingEngine(FakeTransport(), isDemo = false)
        val item = coding(
            ValueType.BOOLEAN,
            EcuMap(
                dataIdentifier = 0x3000,
                byteOffset = 0,
                bitMask = 0x01,
                encodedValues = mapOf("true" to "0x01", "false" to "0x00"),
                verified = false
            ),
            defaultValue = "false"
        )
        val ex = assertThrows(EcuException::class.java) {
            engine.applyCoding(module, item, "true")
        }
        assertTrue(ex.message!!.contains("not verified"))
    }

    @Test
    fun shortBlock_refusesToGrow() {
        val transport = FakeTransport()
        transport.putCoding(0x72, 0x3000, byteArrayOf(0x00)) // only 1 byte
        val engine = CodingEngine(transport, isDemo = true)
        val item = coding(
            ValueType.INTEGER,
            EcuMap(dataIdentifier = 0x3000, byteOffset = 3, bitMask = 0xFF, verified = true),
            defaultValue = "0"
        )
        val ex = assertThrows(EcuException::class.java) {
            engine.applyCoding(module, item, "1")
        }
        assertTrue(ex.message!!.contains("outside"))
    }

    @Test
    fun multiByteHex_rejected() {
        val engine = CodingEngine(FakeTransport(), isDemo = true)
        val item = coding(
            ValueType.HEX,
            EcuMap(dataIdentifier = 0x3000, byteOffset = 0, bitMask = 0xFF, verified = true),
            defaultValue = "00",
            hexLength = 4
        )
        val ex = assertThrows(EcuException::class.java) {
            engine.applyCoding(module, item, "0x1234")
        }
        assertTrue(ex.message!!.contains("Multi-byte"))
    }
}
