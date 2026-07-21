package com.bmwf10.coding.data

import android.content.Context
import com.bmwf10.coding.data.db.AppDatabase
import com.bmwf10.coding.data.db.CodingDao
import com.bmwf10.coding.data.db.CodingEntity
import com.bmwf10.coding.data.db.CodingValueEntity
import com.bmwf10.coding.data.db.ModuleEntity
import com.bmwf10.coding.data.model.CodingItem
import com.bmwf10.coding.data.model.EcuMap
import com.bmwf10.coding.data.model.EnumOption
import com.bmwf10.coding.data.model.Module
import com.bmwf10.coding.data.model.ValueType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for module/coding definitions and their current values.
 *
 * Definitions live in Room (seeded once from the bundled JSON asset). Current values also
 * live in Room, so the app is fully offline and testable with no hardware.
 */
class CodingRepository private constructor(
    private val dao: CodingDao,
    private val context: Context
) {
    private val gson = Gson()

    /** Seed the database from the JSON asset the first time the app runs. */
    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        if (dao.moduleCount() > 0) return@withContext
        val data = CodingAssetLoader.load(context)
        dao.seedDefinitions(
            data.modules.map { it.toEntity() },
            data.codings.map { it.toEntity() }
        )
    }

    /**
     * Populate the local value store with each coding's `demoValue` (falling back to its
     * default). Called when entering demo mode so cards show realistic "current values"
     * with no car attached.
     */
    suspend fun seedDemoValues() = withContext(Dispatchers.IO) {
        val codings = dao.getModules().flatMap { dao.getCodingsForModule(it.id) }
        val now = System.currentTimeMillis()
        codings.forEach { c ->
            val v = c.demoValue ?: c.defaultValue
            dao.upsertValue(CodingValueEntity(c.id, v, now))
        }
    }

    suspend fun clearValues() = withContext(Dispatchers.IO) { dao.clearValues() }

    suspend fun getModules(): List<Module> = withContext(Dispatchers.IO) {
        dao.getModules().map { it.toModel() }
    }

    suspend fun getModule(id: String): Module? = withContext(Dispatchers.IO) {
        dao.getModule(id)?.toModel()
    }

    suspend fun getCodingsForModule(moduleId: String): List<CodingItem> =
        withContext(Dispatchers.IO) { dao.getCodingsForModule(moduleId).map { it.toModel() } }

    suspend fun getCoding(id: String): CodingItem? = withContext(Dispatchers.IO) {
        dao.getCoding(id)?.toModel()
    }

    suspend fun codingCount(moduleId: String): Int =
        withContext(Dispatchers.IO) { dao.codingCountForModule(moduleId) }

    /** Current value for a coding, falling back to its factory default. */
    suspend fun getValue(coding: CodingItem): String = withContext(Dispatchers.IO) {
        dao.getValue(coding.id) ?: coding.defaultValue
    }

    /** Persist the value locally after a successful write (or in demo mode). */
    suspend fun setValue(codingId: String, value: String) = withContext(Dispatchers.IO) {
        dao.upsertValue(CodingValueEntity(codingId, value, System.currentTimeMillis()))
    }

    // --- mapping helpers ---

    private fun Module.toEntity() =
        ModuleEntity(id, name, fullName, description, iconName, diagAddress)

    private fun ModuleEntity.toModel() =
        Module(id, name, fullName, description, iconName, diagAddress)

    private fun CodingItem.toEntity() = CodingEntity(
        id = id,
        moduleId = moduleId,
        name = name,
        description = description,
        longDescription = longDescription,
        valueType = valueType.name,
        defaultValue = defaultValue,
        safeDefault = safeDefault,
        demoValue = demoValue,
        optionsJson = options?.let { gson.toJson(it) },
        min = min,
        max = max,
        unit = unit,
        hexLength = hexLength,
        irreversible = irreversible,
        warning = warning,
        f10Applicable = f10Applicable,
        ecuMapJson = ecuMap?.let { gson.toJson(it) }
    )

    private fun CodingEntity.toModel(): CodingItem {
        val opts: List<EnumOption>? = optionsJson?.let {
            gson.fromJson(it, object : TypeToken<List<EnumOption>>() {}.type)
        }
        val map: EcuMap? = ecuMapJson?.let { gson.fromJson(it, EcuMap::class.java) }
        return CodingItem(
            id = id,
            moduleId = moduleId,
            name = name,
            description = description,
            longDescription = longDescription,
            valueType = ValueType.valueOf(valueType),
            defaultValue = defaultValue,
            safeDefault = safeDefault,
            demoValue = demoValue,
            options = opts,
            min = min,
            max = max,
            unit = unit,
            hexLength = hexLength,
            irreversible = irreversible,
            warning = warning,
            f10Applicable = f10Applicable,
            ecuMap = map
        )
    }

    companion object {
        @Volatile private var instance: CodingRepository? = null

        fun get(context: Context): CodingRepository =
            instance ?: synchronized(this) {
                instance ?: CodingRepository(
                    AppDatabase.get(context).codingDao(),
                    context.applicationContext
                ).also { instance = it }
            }
    }
}
