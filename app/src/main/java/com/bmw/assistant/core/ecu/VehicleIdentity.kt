package com.bmw.assistant.core.ecu

/**
 * What we learned from the car (or demo) after connecting: VIN, an optional
 * I-level string, and which known modules answered a session-control ping.
 */
data class VehicleIdentity(
    val vin: String? = null,
    val iLevel: String? = null,
    val presentModuleIds: Set<String> = emptySet(),
    val probeComplete: Boolean = false
) {
    val shortVin: String?
        get() = vin?.takeIf { it.length >= 7 }?.takeLast(7)

    fun isModulePresent(moduleId: String): Boolean =
        !probeComplete || presentModuleIds.isEmpty() || presentModuleIds.contains(moduleId)
}
