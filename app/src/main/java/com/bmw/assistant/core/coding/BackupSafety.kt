package com.bmw.assistant.core.coding

import com.bmw.assistant.data.model.BackupSource
import com.bmw.assistant.data.model.CodingBackup

/** Pure restore gates so unit tests can cover demo/hardware isolation and VIN matching. */
object BackupSafety {

    fun refuseReason(
        backup: CodingBackup,
        connectionSource: BackupSource,
        connectedVin: String?
    ): String? {
        if (backup.source != connectionSource) {
            return if (backup.source == BackupSource.DEMO)
                "This backup was captured in demo mode and cannot be written to a real car."
            else
                "This backup was captured from real hardware and cannot be restored in demo mode."
        }
        if (!connectedVin.isNullOrBlank() && !backup.vin.isNullOrBlank() &&
            !connectedVin.equals(backup.vin, ignoreCase = true)
        ) {
            return "This backup belongs to VIN ${backup.vin}, not the connected car ($connectedVin)."
        }
        return null
    }
}
