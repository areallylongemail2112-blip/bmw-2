package com.bmw.assistant.data.model

/**
 * A single codeable feature within a [Module].
 *
 * The [longDescription] is the plain-English "What does this do?" text surfaced in the
 * expandable section on every coding card and on the edit screen.
 */
data class CodingItem(
    val id: String,
    val moduleId: String,
    val name: String,
    val description: String,
    val longDescription: String,
    val valueType: ValueType,
    val defaultValue: String,
    val safeDefault: String,
    val demoValue: String? = null,
    val options: List<EnumOption>? = null,
    val min: Int? = null,
    val max: Int? = null,
    val unit: String? = null,
    val hexLength: Int? = null,
    val irreversible: Boolean = false,
    val warning: String? = null,
    val f10Applicable: Boolean = true,
    val ecuMap: EcuMap? = null
) {
    /** Human-readable rendering of a stored raw value (maps enum values to labels). */
    fun displayValue(raw: String): String = when (valueType) {
        ValueType.BOOLEAN -> if (raw.equals("true", true)) "On" else "Off"
        ValueType.ENUM -> options?.firstOrNull { it.value == raw }?.label ?: raw
        ValueType.INTEGER -> if (unit.isNullOrBlank()) raw else "$raw $unit"
        ValueType.HEX -> "0x${raw.removePrefix("0x").uppercase()}"
    }
}

/** Root shape of the bundled `codings_f10.json` asset. */
/**
 * The bundled coding catalog.
 *
 * [assetVersion] is bumped whenever the modules or coding maps in `codings_f10.json` change.
 * [com.bmw.assistant.data.CodingRepository] re-seeds the database when the asset's version is
 * newer than the one already stored, so an app update actually delivers corrected maps to
 * existing installs instead of only to fresh ones.
 */
data class CodingsData(
    val assetVersion: Int = 1,
    val modules: List<Module>,
    val codings: List<CodingItem>
)
