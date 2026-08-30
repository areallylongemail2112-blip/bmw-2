package com.bmw.assistant.data

import com.bmw.assistant.data.model.AssetSchema
import com.bmw.assistant.data.model.CodingsData
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * Parses a user-supplied coding-map JSON file. Accepts the same shape as
 * `codings_f10.json` (object with `codings` / optional `modules` / optional VIN)
 * or a bare array of coding objects.
 */
object VerifiedMapImporter {
    private val gson = Gson()

    fun parse(json: String): CodingsData {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("Not valid JSON: ${e.message}")
        }
        val data = when {
            root.isJsonArray -> CodingsData(
                codings = gson.fromJson(root, Array<com.bmw.assistant.data.model.CodingItem>::class.java).toList()
            )
            root.isJsonObject -> gson.fromJson(root, CodingsData::class.java)
            else -> throw IllegalArgumentException("Expected a JSON object or array of coding maps.")
        }
        if (data.codings.isEmpty() && data.modules.isEmpty()) {
            throw IllegalArgumentException("File contains no modules or coding maps.")
        }
        AssetSchema.validateCodings(data)
        return data
    }
}
