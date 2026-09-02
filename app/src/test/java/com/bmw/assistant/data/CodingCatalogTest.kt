package com.bmw.assistant.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodingCatalogTest {

    private fun catalog(): JsonObject {
        val file = File("src/main/assets/codings_f10.json")
        assertTrue("catalog missing at ${file.absolutePath}", file.exists())
        return JsonParser.parseString(file.readText()).asJsonObject
    }

    @Test
    fun assetVersionIsSet() {
        assertEquals(4, catalog().get("assetVersion").asInt)
    }

    @Test
    fun screenshotCategoriesArePresent() {
        val names = catalog().getAsJsonArray("modules").map {
            it.asJsonObject.get("fullName").asString
        }.toSet()
        val expected = listOf(
            "Active Sound Design",
            "Advanced Crash Safety Module",
            "Air Conditioning",
            "Allround View Camera",
            "Electronic Transmission Control",
            "Front Electronic Module",
            "Headunit",
            "Instrument Cluster",
            "Integrated Chassis Management",
            "Rear Electronic Module",
            "Roof Function Center",
            "Seat Module Driver",
            "Tailgate Function Module"
        )
        for (name in expected) {
            assertTrue("missing control unit: $name", names.contains(name))
        }
    }

    @Test
    fun screenshotFunctionsHaveACoding() {
        val json = catalog()
        val modules = json.getAsJsonArray("modules").associate {
            val o = it.asJsonObject
            o.get("id").asString to o
        }
        val codings = json.getAsJsonArray("codings")
        fun has(moduleId: String, needle: String) = codings.any { c ->
            val o = c.asJsonObject
            o.get("moduleId").asString == moduleId &&
                (o.get("name").asString + " " + o.get("description").asString)
                    .contains(needle, ignoreCase = true)
        }

        assertEquals("FRM", modules.getValue("frm").get("name").asString)
        assertEquals(0x72, modules.getValue("frm").get("diagAddress").asInt)
        assertEquals(0x00, modules.getValue("jbbf").get("diagAddress").asInt)
        assertTrue(has("acsm", "Seat belt"))
        assertTrue(has("trsvc", "Side view"))
        assertTrue(has("icm", "Cruise") || has("icm", "Driving mode"))
        assertTrue(has("fzd", "Alarm") || has("fzd", "Acoustical"))
        assertTrue(has("sm_fa", "Seat heat"))
        assertTrue(has("hkl", "Tailgate"))
        assertTrue(has("dme", "Start-Stop") || has("dme", "Start Stop"))
        assertTrue(has("jbbf", "PDC") || has("pdc", "PDC"))
        assertTrue(has("frm", "mirror") || has("frm", "Mirror"))

        // New v4 items
        assertTrue(has("frm", "DRL") && has("frm", "Source Mode") || has("frm", "DRL Source"))
        assertTrue(has("frm", "Angel") && has("frm", "Brightness") || has("frm", "angel-eye brightness"))
        assertTrue(has("frm", "Welcome Lamps") || has("frm", "welcome lamps"))
        assertTrue(has("frm", "Brake") || has("frm", "brake flash") || has("frm", "ESS"))
        assertTrue(has("frm", "Ambient") && has("frm", "Brightness") || has("frm", "ambient brightness"))
        assertTrue(has("cas", "Start-Stop") || has("cas", "MSA"))
        assertTrue(has("cas", "Comfort Access Close") || has("cas", "Comfort Access") && has("cas", "Close"))
        assertTrue(has("cas", "Key Removal") || has("cas", "key out") || has("cas", "Unlock on Key"))
        assertTrue(has("hu", "disclaimer") || has("hu", "Disclaimer") || has("hu", "legal"))
        assertTrue(has("hu", "Screen Off") || has("hu", "screen off"))
        assertTrue(has("hu", "TPMS") || has("hu", "Tire Pressure") || has("hu", "tire pressure"))
        assertTrue(has("icm", "distance") || has("icm", "Distance") || has("icm", "following"))
        assertTrue(has("hud", "turn") || has("hud", "Turn signal") || has("hud", "indicator"))
        assertTrue(has("acsm", "chime") || has("acsm", "Chime") || has("acsm", "initial"))
        assertTrue(has("jbbf", "washer") || has("jbbf", "Washer") || has("jbbf", "headlight wash"))
        assertTrue(has("jbbf", "rain") || has("jbbf", "Rain") || has("jbbf", "sensor sensitivity"))
        assertTrue(has("jbbf", "seat") || has("jbbf", "Seat") || has("jbbf", "heat memory"))
        assertTrue(has("hkl", "interior") || has("hkl", "Interior") || has("hkl", "Close from Interior"))
        assertTrue(has("hkl", "short") || has("hkl", "Short Press") || has("hkl", "fob close"))
    }

    @Test
    fun noBundledMapIsVerified() {
        for (el in catalog().getAsJsonArray("codings")) {
            val map = el.asJsonObject.getAsJsonObject("ecuMap")
            assertFalse(el.asJsonObject.get("id").asString, map.get("verified").asBoolean)
            assertTrue(map.get("dataIdentifier").asInt > 0)
        }
        assertTrue(catalog().getAsJsonArray("codings").size() >= 85)
    }
}
