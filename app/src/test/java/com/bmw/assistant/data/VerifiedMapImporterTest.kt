package com.bmw.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedMapImporterTest {

    @Test
    fun parsesObjectWithCodingsArray() {
        val json = """
            { "codings": [
                { "id": "frm_cornering_lights",
                  "ecuMap": { "dataIdentifier": 12288, "byteOffset": 0, "bitMask": 1,
                              "encodedValues": { "true": "0x01", "false": "0x00" },
                              "verified": false } }
            ] }
        """.trimIndent()
        val patches = VerifiedMapImporter.parse(json)
        assertEquals(1, patches.size)
        assertEquals("frm_cornering_lights", patches[0].id)
        assertEquals(12288, patches[0].ecuMap.dataIdentifier)
    }

    @Test
    fun parsesBareArray() {
        val json = """[ { "id": "a", "ecuMap": { "dataIdentifier": 1, "byteOffset": 0, "bitMask": 1 } } ]"""
        assertEquals("a", VerifiedMapImporter.parse(json).single().id)
    }

    @Test
    fun rejectsEmptyOrGarbage() {
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedMapImporter.parse("""{ "codings": [] }""")
        }
        assertThrows(Exception::class.java) {
            VerifiedMapImporter.parse("not json")
        }
    }

    @Test
    fun skipsEntriesWithoutId() {
        val json = """[ { "ecuMap": { "dataIdentifier": 1 } },
                        { "id": "ok", "ecuMap": { "dataIdentifier": 2, "byteOffset": 1, "bitMask": 255 } } ]"""
        val patches = VerifiedMapImporter.parse(json)
        assertEquals(1, patches.size)
        assertTrue(patches[0].id == "ok")
    }
}
