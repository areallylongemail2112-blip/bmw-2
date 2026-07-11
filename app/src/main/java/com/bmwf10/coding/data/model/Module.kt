package com.bmwf10.coding.data.model

/**
 * A codeable ECU / control module (FRM, KOMBI, NBT, HUD, CAS ...).
 *
 * [diagAddress] is the ECU's DoIP/UDS diagnostic address (logical address) used when
 * talking to the car over ENET. It is only meaningful for real hardware transports;
 * demo mode ignores it. Values here are the well-known F-series addresses.
 */
data class Module(
    val id: String,
    val name: String,
    val fullName: String,
    val description: String,
    val iconName: String,
    val diagAddress: Int = 0
)
