package com.bmw.assistant.data

import com.bmw.assistant.data.model.EcuMap
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One verified-map overlay keyed by a catalog coding id. */
data class VerifiedMapPatch(
    val id: String,
    val ecuMap: EcuMap
)

/**
 * Parses a user-supplied JSON file of verified coding maps. Accepted shapes:
 *
 *   { "codings": [ { "id": "frm_cornering_lights", "ecuMap": { ... } }, ... ] }
 *   [ { "id": "...", "ecuMap": { ... } }, ... ]
 *
 * Hardware writes stay blocked until [EcuMap.verified] is true; this importer always marks
 * imported maps verified because the user is asserting they came from their own CAFD/NCD.
 */
object VerifiedMapImporter {
    private val gson = Gson()

    fun parse(json: String): List<VerifiedMapPatch> {
        val root = JsonParser.parseString(json)
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && root.asJsonObject.has("codings") ->
                root.asJsonObject.getAsJsonArray("codings")
            else -> throw IllegalArgumentException(
                "Expected a JSON array or an object with a \"codings\" array."
            )
        }
        val out = ArrayList<VerifiedMapPatch>()
        for (el in array) {
            if (!el.isJsonObject) continue
            val obj: JsonObject = el.asJsonObject
            val id = obj.get("id")?.asString ?: continue
            val mapEl = obj.get("ecuMap") ?: continue
            val map = gson.fromJson(mapEl, EcuMap::class.java)
                ?: throw IllegalArgumentException("Invalid ecuMap for $id")
            out += VerifiedMapPatch(id, map)
        }
        if (out.isEmpty()) throw IllegalArgumentException("No coding maps found in the file.")
        return out
    }
}
