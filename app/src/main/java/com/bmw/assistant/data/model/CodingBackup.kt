package com.bmw.assistant.data.model

/**
 * A snapshot of one module coding block (the raw bytes of a UDS DID) taken before it was
 * modified — the app's restore point system, modeled on the "create a backup before coding,
 * restore it to revert" workflow that established BMW coding tools use.
 *
 * Backups are captured automatically right before every coding write and can also be taken
 * manually from the Backups screen. Restoring writes the exact saved bytes back to the module.
 *
 * @param source where the bytes came from (demo vs. real hardware). A backup can only be
 *   restored onto the same kind of connection: demo snapshots never reach a real car, and
 *   hardware snapshots are not applied to the simulator.
 * @param vin the car the bytes were read from, when it could be identified. A hardware backup
 *   is refused on a car with a different VIN — coding bytes are only meaningful for the exact
 *   module they came from.
 */
data class CodingBackup(
    val id: Long,
    val moduleId: String,
    val moduleName: String,
    val diagAddress: Int,
    val dataIdentifier: Int,
    val blockHex: String,
    val label: String,
    val source: BackupSource,
    val connectionLabel: String?,
    val createdAt: Long,
    val vin: String? = null
) {
    val blockSize: Int get() = blockHex.length / 2
}

enum class BackupSource { DEMO, HARDWARE }
