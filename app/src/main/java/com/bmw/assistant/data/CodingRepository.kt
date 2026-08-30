package com.bmw.assistant.data

import android.content.Context
import com.bmw.assistant.data.db.AppDatabase
import com.bmw.assistant.data.db.CodingBackupEntity
import com.bmw.assistant.data.db.CodingDao
import com.bmw.assistant.data.db.CodingEntity
import com.bmw.assistant.data.db.CodingValueEntity
import com.bmw.assistant.data.db.ModuleEntity
import com.bmw.assistant.data.model.BackupSource
import com.bmw.assistant.data.model.CodingBackup
import com.bmw.assistant.data.model.CodingItem
import com.bmw.assistant.data.model.CodingsData
import com.bmw.assistant.data.model.EcuMap
import com.bmw.assistant.data.model.EnumOption
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.data.model.StoredValue
import com.bmw.assistant.data.model.ValueSource
import com.bmw.assistant.data.model.ValueType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for module/coding definitions and their current values.
 *
 * Definitions live in Room (seeded from the bundled JSON asset, and re-seeded when
 * the asset version bumps). Current values also live in Room, tagged with a
 * [ValueSource] so the UI can tell a live ECU read from a cached default.
 */
class CodingRepository private constructor(
    private val dao: CodingDao,
    private val context: Context
) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Seed or refresh definitions when the bundled asset version is newer. */
    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        val data = CodingAssetLoader.load(context)
        val last = prefs.getInt(KEY_ASSET_VERSION, 0)
        if (dao.moduleCount() > 0 && last >= data.assetVersion) return@withContext
        dao.insertModules(data.modules.map { it.toEntity() })
        dao.insertCodings(data.codings.map { it.toEntity() })
        prefs.edit().putInt(KEY_ASSET_VERSION, data.assetVersion).apply()
    }

    /**
     * Merge an imported [CodingsData] into Room. Codings with the same id replace
     * the stored definition (so a verified map can unlock a bundled illustrative one).
     * New modules are inserted. Existing values and backups are left alone.
     * @return number of coding rows written.
     */
    suspend fun importMaps(data: CodingsData): Int = withContext(Dispatchers.IO) {
        if (data.modules.isNotEmpty()) {
            dao.insertModules(data.modules.map { it.toEntity() })
        }
        if (data.codings.isNotEmpty()) {
            dao.insertCodings(data.codings.map { it.toEntity() })
        }
        data.codings.size
    }

    /**
     * Populate the local value store with each coding's `demoValue` (falling back to its
     * default). Called when entering demo mode so cards show realistic "current values"
     * with no car attached. Tagged [ValueSource.FROM_CAR] because the demo transport
     * *is* the car for this session.
     */
    suspend fun seedDemoValues() = withContext(Dispatchers.IO) {
        val codings = dao.getModules().flatMap { dao.getCodingsForModule(it.id) }
        val now = System.currentTimeMillis()
        codings.forEach { c ->
            val v = c.demoValue ?: c.defaultValue
            dao.upsertValue(CodingValueEntity(c.id, v, now, ValueSource.FROM_CAR.name))
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
    suspend fun getValue(coding: CodingItem): String = getStoredValue(coding).value

    suspend fun getStoredValue(coding: CodingItem): StoredValue = withContext(Dispatchers.IO) {
        val row = dao.getValueRow(coding.id)
        if (row == null) StoredValue(coding.defaultValue, ValueSource.DEFAULT)
        else StoredValue(
            row.value,
            runCatching { ValueSource.valueOf(row.source) }.getOrDefault(ValueSource.LOCAL_CACHE)
        )
    }

    /** Persist the value locally after a successful write (or in demo mode). */
    suspend fun setValue(
        codingId: String,
        value: String,
        source: ValueSource = ValueSource.LOCAL_CACHE
    ) = withContext(Dispatchers.IO) {
        dao.upsertValue(CodingValueEntity(codingId, value, System.currentTimeMillis(), source.name))
    }

    // --- backups ---

    /**
     * Stores a coding-block snapshot unless the latest backup of the same block (on the same
     * source) already holds identical bytes — repeated edits don't pile up duplicates.
     * @return true if a new backup row was written.
     */
    suspend fun addBackupIfChanged(
        module: Module,
        dataIdentifier: Int,
        blockHex: String,
        label: String,
        source: BackupSource,
        connectionLabel: String?,
        vin: String? = null,
        iLevel: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val latest = dao.latestBackupForBlock(module.id, dataIdentifier, source.name)
        if (latest?.blockHex == blockHex) return@withContext false
        dao.insertBackup(
            CodingBackupEntity(
                moduleId = module.id,
                moduleName = module.name,
                diagAddress = module.diagAddress,
                dataIdentifier = dataIdentifier,
                blockHex = blockHex,
                label = label,
                source = source.name,
                connectionLabel = connectionLabel,
                vin = vin,
                iLevel = iLevel,
                createdAt = System.currentTimeMillis()
            )
        )
        true
    }

    suspend fun getBackups(): List<CodingBackup> = withContext(Dispatchers.IO) {
        dao.getBackups().map { it.toModel() }
    }

    suspend fun deleteBackup(id: Long) = withContext(Dispatchers.IO) { dao.deleteBackup(id) }

    // --- mapping helpers ---

    private fun CodingBackupEntity.toModel() = CodingBackup(
        id = id,
        moduleId = moduleId,
        moduleName = moduleName,
        diagAddress = diagAddress,
        dataIdentifier = dataIdentifier,
        blockHex = blockHex,
        label = label,
        source = runCatching { BackupSource.valueOf(source) }.getOrDefault(BackupSource.DEMO),
        connectionLabel = connectionLabel,
        vin = vin,
        iLevel = iLevel,
        createdAt = createdAt
    )

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
        private const val PREFS = "bmw_assistant_meta"
        private const val KEY_ASSET_VERSION = "coding_asset_version"

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
