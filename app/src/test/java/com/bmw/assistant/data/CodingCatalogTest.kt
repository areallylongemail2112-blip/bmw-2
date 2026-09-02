package com.bmw.assistant.data

import com.google.gson.Gson
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
        assertEquals(3, catalog().get("assetVersion").asInt)
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
    }

    @Test
    fun noBundledMapIsVerified() {
        val gson = Gson()
        for (el in catalog().getAsJsonArray("codings")) {
            val map = el.asJsonObject.getAsJsonObject("ecuMap")
            assertFalse(el.asJsonObject.get("id").asString, map.get("verified").asBoolean)
            assertTrue(map.get("dataIdentifier").asInt > 0)
        }
        assertTrue(catalog().getAsJsonArray("codings").size() >= 60)
    }
}
