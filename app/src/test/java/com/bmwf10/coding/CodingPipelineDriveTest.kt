package com.bmwf10.coding

import com.bmwf10.coding.data.model.CodingItem
import com.bmwf10.coding.data.model.CodingsData
import com.bmwf10.coding.data.model.EcuMap
import com.bmwf10.coding.data.model.ValueType
import com.bmwf10.coding.ecu.CodingEngine
import com.bmwf10.coding.ecu.DemoTransport
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Headless drive of the app's real "write a code" pipeline: the production [CodingEngine] and
 * [DemoTransport] classes operating on the real bundled `codings_f10.json` definitions. This is
 * the closest thing to running the app on the coding path when no emulator/device is available
 * (no KVM for x86 images; the modern emulator won't run ARM images on an x86_64 host).
 */
class CodingPipelineDriveTest {

    private fun loadData(): CodingsData {
        val candidates = listOf(
            "src/main/assets/codings_f10.json",
            "app/src/main/assets/codings_f10.json"
        )
        val file = candidates.map { File(it) }.first { it.exists() }
        return Gson().fromJson(file.readText(), CodingsData::class.java)
    }

    @Test
    fun drive_demo_coding_writes_expected_bytes() {
        val data = loadData()
        val transport = DemoTransport()
        transport.connect()
        val engine = CodingEngine(transport, isDemo = true)

        fun module(id: String) = data.modules.first { it.id == id }
        fun coding(id: String) = data.codings.first { it.id == id }

        println("=== Driving demo coding pipeline against real JSON (${data.codings.size} codings) ===")

        // 1) Boolean: cornering lights OFF -> ON. bitMask 0x01, true -> 0x01.
        val corner = coding("frm_cornering_lights")
        val bBefore = engine.readCoding(module("frm"), corner)
        val bByte = engine.applyCoding(module("frm"), corner, "true")
        val bAfter = engine.readCoding(module("frm"), corner)
        println("BOOLEAN  ${corner.name}: before=$bBefore -> wrote byte 0x%02X -> reads=%s"
            .format(bByte.toInt() and 0xFF, bAfter))
        assertEquals(ValueType.BOOLEAN, corner.valueType)
        assertEquals(0x01, bByte.toInt() and 0xFF)
        assertEquals("true", bAfter)

        // 2) Integer: DRL brightness -> 90 (scale 1, whole byte). Expect raw 90 = 0x5A.
        val drl = coding("frm_drl_brightness")
        val iByte = engine.applyCoding(module("frm"), drl, "90")
        val iAfter = engine.readCoding(module("frm"), drl)
        println("INTEGER  ${drl.name}: wrote byte 0x%02X (%d) -> reads=%s %s"
            .format(iByte.toInt() and 0xFF, iByte.toInt() and 0xFF, iAfter, drl.unit ?: ""))
        assertEquals(90, iByte.toInt() and 0xFF)
        assertEquals("90", iAfter)

        // 3) Enum: ambient color -> red (0x03 per ecuMap).
        val amb = coding("frm_ambient_color")
        val eByte = engine.applyCoding(module("frm"), amb, "red")
        val eAfter = engine.readCoding(module("frm"), amb)
        println("ENUM     ${amb.name}: wrote byte 0x%02X -> reads=%s".format(eByte.toInt() and 0xFF, eAfter))
        assertEquals(0x03, eByte.toInt() and 0xFF)
        assertEquals("red", eAfter)

        // 4) Integer with scale 2: HUD speed warning 130 km/h -> raw 65 = 0x41.
        val hud = coding("hud_speed_warning")
        val hByte = engine.applyCoding(module("hud"), hud, "130")
        println("INTEGER  ${hud.name}: 130 km/h (scale 2) -> wrote byte 0x%02X (%d) -> reads=%s km/h"
            .format(hByte.toInt() and 0xFF, hByte.toInt() and 0xFF, engine.readCoding(module("hud"), hud)))
        assertEquals(65, hByte.toInt() and 0xFF)

        // 5) Bit-packing safety: two features share the same DID block; writing one must not
        //    corrupt the other. cornering(byte0,bit0) and farewell(byte4,bit0) live in DID 0x3000.
        val farewell = coding("frm_farewell_animation")
        engine.applyCoding(module("frm"), farewell, "false")
        val cornerStill = engine.readCoding(module("frm"), corner)
        println("PACKING  cornering still=$cornerStill after writing a different byte in same block")
        assertEquals("true", cornerStill)

        // 6) Safety gate: on a real (non-demo) transport, an unverified map must be refused.
        val realish = CodingEngine(DemoTransport().also { it.connect() }, isDemo = false)
        val blocked = try { realish.applyCoding(module("frm"), corner, "true"); "NOT BLOCKED" }
        catch (e: Exception) { "blocked: ${e.message?.take(60)}" }
        println("GATE     unverified map on hardware -> $blocked")
        assertTrue(blocked.startsWith("blocked"))

        // 7) Sub-byte INTEGER field (bitMask 0xF0): value must be shifted into the high
        //    nibble on write and shifted back on read (regression guard for review #5).
        val subByte = CodingItem(
            id = "test_subbyte", moduleId = "frm", name = "High-nibble Field",
            description = "", longDescription = "", valueType = ValueType.INTEGER,
            defaultValue = "0", safeDefault = "0", min = 0, max = 15,
            ecuMap = EcuMap(dataIdentifier = 0x9999, byteOffset = 0, bitMask = 0xF0, verified = false)
        )
        val sbByte = engine.applyCoding(module("frm"), subByte, "5")
        val sbRead = engine.readCoding(module("frm"), subByte)
        println("SHIFT    high-nibble int (mask 0xF0) value 5 -> wrote 0x%02X -> reads=%s"
            .format(sbByte.toInt() and 0xFF, sbRead))
        assertEquals(0x50, sbByte.toInt() and 0xFF)
        assertEquals("5", sbRead)

        // 8) Out-of-range byteOffset must refuse rather than pad/grow the coding block.
        val oob = CodingItem(
            id = "test_oob", moduleId = "frm", name = "Past End",
            description = "", longDescription = "", valueType = ValueType.BOOLEAN,
            defaultValue = "false", safeDefault = "false",
            ecuMap = EcuMap(
                dataIdentifier = 0x3000, byteOffset = 64, bitMask = 0x01,
                encodedValues = mapOf("true" to "0x01", "false" to "0x00"),
                verified = false
            )
        )
        val oobBlocked = try {
            engine.applyCoding(module("frm"), oob, "true"); "NOT BLOCKED"
        } catch (e: Exception) {
            "blocked: ${e.message?.take(80)}"
        }
        println("BOUNDS   oversized byteOffset -> $oobBlocked")
        assertTrue(oobBlocked.startsWith("blocked"))

        // 9) INTEGER that does not fit the bitMask width must refuse (no silent truncation).
        val overflow = CodingItem(
            id = "test_overflow", moduleId = "frm", name = "Nibble Overflow",
            description = "", longDescription = "", valueType = ValueType.INTEGER,
            defaultValue = "0", safeDefault = "0", min = 0, max = 255,
            ecuMap = EcuMap(dataIdentifier = 0x9998, byteOffset = 0, bitMask = 0x0F, verified = false)
        )
        val overflowBlocked = try {
            engine.applyCoding(module("frm"), overflow, "16"); "NOT BLOCKED"
        } catch (e: Exception) {
            "blocked: ${e.message?.take(80)}"
        }
        println("WIDTH    int 16 into mask 0x0F -> $overflowBlocked")
        assertTrue(overflowBlocked.startsWith("blocked"))

        println("=== All coding-pipeline drives passed ===")
        transport.disconnect()
    }
}
