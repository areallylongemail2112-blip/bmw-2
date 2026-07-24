package com.bmwf10.coding.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room copy of a [com.bmwf10.coding.data.model.Module]. Definitions are seeded from the
 * bundled JSON asset on first launch so the app is fully self-contained (no cloud).
 */
@Entity(tableName = "modules")
data class ModuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fullName: String,
    val description: String,
    val iconName: String,
    val diagAddress: Int
)

/**
 * Room copy of a [com.bmwf10.coding.data.model.CodingItem]. Complex fields (options, ecuMap)
 * are stored as JSON via [Converters].
 */
@Entity(tableName = "codings")
data class CodingEntity(
    @PrimaryKey val id: String,
    val moduleId: String,
    val name: String,
    val description: String,
    val longDescription: String,
    val valueType: String,
    val defaultValue: String,
    val safeDefault: String,
    val demoValue: String?,
    val optionsJson: String?,
    val min: Int?,
    val max: Int?,
    val unit: String?,
    val hexLength: Int?,
    val irreversible: Boolean,
    val warning: String?,
    val f10Applicable: Boolean,
    val ecuMapJson: String?
)

/**
 * The user's current value for a coding. This is the app's local mirror of what has been
 * (or would be) written to the car. Populated in demo mode with fake values, and updated
 * after a confirmed write to real hardware.
 */
@Entity(tableName = "coding_values")
data class CodingValueEntity(
    @PrimaryKey val codingId: String,
    val value: String,
    val updatedAt: Long
)

/**
 * Room copy of a [com.bmwf10.coding.data.model.CodingBackup]: the raw bytes of one module
 * coding block captured before a write, so the exact original data can be restored.
 * [source] is the [com.bmwf10.coding.data.model.BackupSource] name (DEMO / HARDWARE).
 */
@Entity(tableName = "coding_backups")
data class CodingBackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleId: String,
    val moduleName: String,
    val diagAddress: Int,
    val dataIdentifier: Int,
    val blockHex: String,
    val label: String,
    val source: String,
    val connectionLabel: String?,
    val createdAt: Long
)
