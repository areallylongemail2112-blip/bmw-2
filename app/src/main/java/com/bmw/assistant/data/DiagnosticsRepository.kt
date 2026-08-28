package com.bmw.assistant.data

import android.content.Context
import com.bmw.assistant.core.ecu.uds.RawDtc
import com.bmw.assistant.data.model.DemoFault
import com.bmw.assistant.data.model.DiagnosticsData
import com.bmw.assistant.data.model.LiveParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for diagnostics definitions: the live-parameter catalog, the DTC
 * description catalog, and the demo fault set. Loaded once from the bundled JSON asset and
 * cached in memory (there is nothing user-mutable here, so it does not need Room).
 */
class DiagnosticsRepository private constructor(private val context: Context) {

    @Volatile private var data: DiagnosticsData? = null

    /** DTC high-16 hex -> description, built lazily from the catalog. */
    @Volatile private var dtcIndex: Map<String, String> = emptyMap()

    suspend fun ensureLoaded(): DiagnosticsData = withContext(Dispatchers.IO) {
        data ?: synchronized(this@DiagnosticsRepository) {
            data ?: DiagnosticsAssetLoader.load(context).also {
                data = it
                dtcIndex = it.dtcCatalog.associate { entry ->
                    normalize(entry.code) to entry.description
                }
            }
        }
    }

    suspend fun liveParametersFor(moduleId: String): List<LiveParameter> =
        ensureLoaded().liveData.filter { it.moduleId == moduleId }

    suspend fun allLiveParameters(): List<LiveParameter> = ensureLoaded().liveData

    suspend fun demoFaults(): List<DemoFault> = ensureLoaded().demoFaults

    /** True if any live parameter is defined for the module. */
    suspend fun hasLiveData(moduleId: String): Boolean =
        ensureLoaded().liveData.any { it.moduleId == moduleId }

    /** Plain-English description for a fault code, or null if it is not in the catalog. */
    fun describe(dtc: RawDtc): String? {
        val key = "%04X".format(dtc.high16)
        return dtcIndex[key]
    }

    private fun normalize(code: String): String {
        val cleaned = code.trim().removePrefix("0x").removePrefix("0X").uppercase()
        // Catalog is keyed on the DTC's high 16 bits (4 hex digits).
        return cleaned.take(4).padStart(4, '0')
    }

    companion object {
        @Volatile private var instance: DiagnosticsRepository? = null

        fun get(context: Context): DiagnosticsRepository =
            instance ?: synchronized(this) {
                instance ?: DiagnosticsRepository(context.applicationContext).also { instance = it }
            }
    }
}
