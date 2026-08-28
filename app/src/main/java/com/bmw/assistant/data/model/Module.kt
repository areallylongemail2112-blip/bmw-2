package com.bmw.assistant.data.model

/**
 * A control module (FRM, KOMBI, NBT, HUD, CAS, DME ...) the app can talk to.
 *
 * [diagAddress] is the ECU's DoIP/UDS diagnostic address (logical address) used when talking
 * to the car over ENET. It is only meaningful for real hardware transports; demo mode ignores
 * it. Values here are the well-known F-series addresses.
 *
 * A module can host coding features, diagnostics (faults + live data), or both — the Home hub
 * shows it under Coding only when it actually has coding entries.
 */
data class Module(
    val id: String,
    val name: String,
    val fullName: String,
    val description: String,
    val iconName: String,
    val diagAddress: Int = 0
)
