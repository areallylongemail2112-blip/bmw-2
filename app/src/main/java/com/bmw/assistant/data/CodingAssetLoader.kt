package com.bmw.assistant.data

import android.content.Context
import com.bmw.assistant.data.model.CodingsData
import com.google.gson.Gson

/** Reads and parses the bundled `assets/codings_f10.json` seed file. */
object CodingAssetLoader {
    private const val ASSET = "codings_f10.json"

    fun load(context: Context): CodingsData {
        val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        return Gson().fromJson(json, CodingsData::class.java)
    }
}
