package com.bmw.assistant.data

import android.content.Context
import com.bmw.assistant.data.model.DiagnosticsData
import com.google.gson.Gson

/** Reads and parses the bundled `assets/diagnostics_f10.json` file (live params + DTC catalog). */
object DiagnosticsAssetLoader {
    private const val ASSET = "diagnostics_f10.json"

    fun load(context: Context): DiagnosticsData {
        val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        return Gson().fromJson(json, DiagnosticsData::class.java)
    }
}
