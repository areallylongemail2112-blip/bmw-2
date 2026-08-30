package com.bmw.assistant.data

import com.bmw.assistant.data.model.AssetSchema
import com.bmw.assistant.data.model.CodingsData
import com.bmw.assistant.data.model.DiagnosticsData
import com.bmw.assistant.data.model.ServicesData
import com.google.gson.Gson
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssetSchemaTest {

    private val gson = Gson()

    @Test
    fun bundledCodingAsset_isValid() {
        val data = gson.fromJson(readAsset("codings_f10.json"), CodingsData::class.java)
        AssetSchema.validateCodings(data)
        assertTrue(data.codings.isNotEmpty())
        assertTrue(data.modules.isNotEmpty())
        assertTrue(data.assetVersion >= 1)
        assertTrue(data.codings.all { it.ecuMap?.verified != true })
    }

    @Test
    fun knowledgeBaseFeatures_areBundled() {
        val data = gson.fromJson(readAsset("codings_f10.json"), CodingsData::class.java)
        val ids = data.codings.map { it.id }.toSet()
        val required = listOf(
            "frm_cornering_lights", "frm_drl", "frm_drl_brightness", "frm_angel_eye_mode",
            "frm_welcome_lights", "frm_farewell_animation", "frm_ambient_color", "frm_comfort_blink",
            "kombi_gauge_sweep", "kombi_digital_speedo", "kombi_oil_temp", "kombi_speedo_unit",
            "kombi_lap_timer", "kombi_startup_gong", "kombi_service_interval",
            "nbt_video_in_motion", "nbt_speed_limit", "nbt_ambient_menu", "nbt_sport_display",
            "nbt_screen_off_timer", "nbt_carplay",
            "hud_speed_unit", "hud_nav_arrows", "hud_brightness", "hud_speed_warning",
            "cas_auto_lock_speed", "cas_remote_window", "cas_comfort_entry",
            "cas_selective_unlock", "cas_horn_on_lock", "cas_auto_relock",
            "kafas_ldw_default"
        )
        val missing = required.filterNot { it in ids }
        assertTrue("Missing coding ids: $missing", missing.isEmpty())
        assertTrue(data.modules.any { it.id == "kafas" })
        assertTrue(data.codings.size >= 37)
    }

    @Test
    fun bundledDiagnosticsAsset_isValid() {
        val data = gson.fromJson(readAsset("diagnostics_f10.json"), DiagnosticsData::class.java)
        AssetSchema.validateDiagnostics(data)
        assertTrue(data.liveData.size >= 9)
        assertTrue(data.dtcCatalog.size >= 15)
    }

    @Test
    fun bundledServicesAsset_isValid() {
        val data = gson.fromJson(readAsset("services_f10.json"), ServicesData::class.java)
        AssetSchema.validateServices(data)
        assertTrue(data.services.size >= 3)
        assertTrue(data.services.all { !it.verified })
    }

    @Test
    fun importer_acceptsBareArray() {
        val json = """[{"id":"x","moduleId":"frm","name":"X","description":"d","longDescription":"ld",
            "valueType":"BOOLEAN","defaultValue":"false","safeDefault":"false",
            "ecuMap":{"dataIdentifier":12288,"byteOffset":0,"bitMask":1,"verified":true}}]"""
        val data = VerifiedMapImporter.parse(json)
        assertTrue(data.codings.single().ecuMap!!.verified)
    }

    @Test(expected = IllegalArgumentException::class)
    fun importer_rejectsEmptyObject() {
        VerifiedMapImporter.parse("""{"assetVersion":1}""")
    }

    private fun readAsset(name: String): String {
        val file = File("src/main/assets/$name")
        assertTrue("missing $name at ${file.absolutePath}", file.exists())
        return file.readText()
    }
}
