package com.bmw.assistant.data.model

/**
 * Structural checks for bundled and imported JSON. Used by unit tests and the
 * in-app map importer so a broken definition cannot reach Room.
 */
object AssetSchema {

    fun validateCodings(data: CodingsData) {
        require(data.assetVersion >= 1) { "assetVersion must be >= 1" }
        val moduleIds = data.modules.map { it.id }.toSet()
        data.modules.forEach { m ->
            require(m.id.isNotBlank()) { "Module id is blank" }
            require(m.diagAddress in 0x01..0xFF) { "Module ${m.id} has invalid diagAddress ${m.diagAddress}" }
        }
        data.codings.forEach { c ->
            require(c.id.isNotBlank()) { "Coding id is blank" }
            require(c.moduleId.isNotBlank()) { "Coding ${c.id} has no moduleId" }
            if (moduleIds.isNotEmpty()) {
                require(c.moduleId in moduleIds) { "Coding ${c.id} references unknown module ${c.moduleId}" }
            }
            val map = c.ecuMap ?: return@forEach
            require(map.byteOffset >= 0) { "Coding ${c.id} has negative byteOffset" }
            require(map.bitMask != 0) { "Coding ${c.id} has bitMask 0" }
            require(map.scale > 0.0) { "Coding ${c.id} has invalid scale" }
            require(map.dataIdentifier in 0..0xFFFF) { "Coding ${c.id} has invalid DID" }
        }
    }

    fun validateDiagnostics(data: DiagnosticsData) {
        require(data.assetVersion >= 1) { "assetVersion must be >= 1" }
        data.liveData.forEach { p ->
            require(p.id.isNotBlank()) { "Live parameter id is blank" }
            require(p.dataIdentifier in 0..0xFFFF) { "Live ${p.id} has invalid DID" }
            require(p.byteLength in 1..4) { "Live ${p.id} has invalid byteLength" }
            require(p.byteOffset >= 0) { "Live ${p.id} has negative byteOffset" }
        }
        data.dtcCatalog.forEach { e ->
            require(e.code.isNotBlank()) { "DTC catalog entry has blank code" }
            require(e.description.isNotBlank()) { "DTC ${e.code} has blank description" }
        }
        data.demoFaults.forEach { f ->
            require(f.dtc.length == 6) { "Demo fault ${f.dtc} must be 6 hex digits" }
        }
    }

    fun validateServices(data: ServicesData) {
        require(data.assetVersion >= 1) { "assetVersion must be >= 1" }
        data.services.forEach { s ->
            require(s.id.isNotBlank()) { "Service id is blank" }
            require(s.moduleId.isNotBlank()) { "Service ${s.id} has no moduleId" }
            require(s.routineId in 0..0xFFFF) { "Service ${s.id} has invalid routineId" }
        }
    }
}
