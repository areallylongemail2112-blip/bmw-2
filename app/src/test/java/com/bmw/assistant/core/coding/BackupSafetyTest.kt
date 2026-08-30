package com.bmw.assistant.core.coding

import com.bmw.assistant.data.model.BackupSource
import com.bmw.assistant.data.model.CodingBackup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSafetyTest {

    private fun backup(source: BackupSource, vin: String? = "WBAFR7C50DC123456") = CodingBackup(
        id = 1,
        moduleId = "frm",
        moduleName = "FRM",
        diagAddress = 0x72,
        dataIdentifier = 0x3000,
        blockHex = "0000",
        label = "test",
        source = source,
        connectionLabel = "demo",
        vin = vin,
        iLevel = "F010-20-03-540",
        createdAt = 0L
    )

    @Test
    fun demoBackup_refusedOnHardware() {
        val reason = BackupSafety.refuseReason(backup(BackupSource.DEMO), BackupSource.HARDWARE, null)
        assertNotNull(reason)
        assertTrue(reason!!.contains("demo mode"))
    }

    @Test
    fun hardwareBackup_refusedInDemo() {
        val reason = BackupSafety.refuseReason(backup(BackupSource.HARDWARE), BackupSource.DEMO, null)
        assertNotNull(reason)
        assertTrue(reason!!.contains("real hardware"))
    }

    @Test
    fun vinMismatch_refused() {
        val reason = BackupSafety.refuseReason(
            backup(BackupSource.DEMO, "WBAOTHERVIN000000"),
            BackupSource.DEMO,
            "WBAFR7C50DC123456"
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("VIN"))
    }

    @Test
    fun matchingVinAndSource_allowed() {
        assertNull(
            BackupSafety.refuseReason(
                backup(BackupSource.DEMO),
                BackupSource.DEMO,
                "WBAFR7C50DC123456"
            )
        )
    }
}
