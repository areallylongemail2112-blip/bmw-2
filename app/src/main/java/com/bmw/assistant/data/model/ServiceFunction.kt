package com.bmw.assistant.data.model

/**
 * A one-shot service job (CBS reset, battery registration, …) issued as UDS
 * RoutineControl (0x31). Like coding maps, bundled routines are illustrative
 * (`verified = false`) and only run on hardware when marked verified.
 */
data class ServiceFunction(
    val id: String,
    val moduleId: String,
    val name: String,
    val description: String,
    val longDescription: String = "",
    val routineId: Int,
    val payloadHex: String? = null,
    val verified: Boolean = false,
    val warning: String? = null
)

data class ServicesData(
    val assetVersion: Int = 1,
    val services: List<ServiceFunction> = emptyList()
)
