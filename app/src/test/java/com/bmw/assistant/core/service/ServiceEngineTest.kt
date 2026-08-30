package com.bmw.assistant.core.service

import com.bmw.assistant.core.ecu.EcuException
import com.bmw.assistant.core.ecu.FakeTransport
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.data.model.ServiceFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceEngineTest {

    private val kombi = Module(
        id = "kombi", name = "KOMBI", fullName = "Cluster",
        description = "t", iconName = "activity", diagAddress = 0x60
    )

    private val oil = ServiceFunction(
        id = "cbs_oil_reset",
        moduleId = "kombi",
        name = "Oil service reset",
        description = "d",
        routineId = 0xAB02,
        verified = false
    )

    @Test
    fun unverified_blockedOnHardware() {
        val ex = assertThrows(EcuException::class.java) {
            ServiceEngine(FakeTransport(), isDemo = false).run(kombi, oil)
        }
        assertTrue(ex.message!!.contains("not verified"))
    }

    @Test
    fun unverified_runsInDemo() {
        val t = FakeTransport()
        ServiceEngine(t, isDemo = true).run(kombi, oil)
        assertEquals(0x60 to 0xAB02, t.lastRoutines.single())
    }

    @Test
    fun verified_runsOnHardware() {
        val t = FakeTransport()
        ServiceEngine(t, isDemo = false).run(kombi, oil.copy(verified = true))
        assertEquals(1, t.lastRoutines.size)
    }
}
