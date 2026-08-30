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
