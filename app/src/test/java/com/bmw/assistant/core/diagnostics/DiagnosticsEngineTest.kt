package com.bmw.assistant.core.diagnostics

import com.bmw.assistant.core.ecu.FakeTransport
import com.bmw.assistant.data.model.LiveParameter
import com.bmw.assistant.data.model.Module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsEngineTest {

    private val dme = Module(
        id = "dme",
        name = "DME",
        fullName = "Engine Control",
        description = "test",
        iconName = "engine",
        diagAddress = 0x12
    )

    @Test
    fun readFaults_returnsSeededDtcs() {
        val transport = FakeTransport()
        transport.putFault(0x12, byteArrayOf(0x2C, 0x6A, 0x08), 0x09)
        val engine = DiagnosticsEngine(transport)

        val faults = engine.readFaults(dme)
        assertEquals(1, faults.size)
        assertEquals("2C6A08", faults[0].hexCode())
    }

    @Test
    fun clearFaults_erasesFaultMemory() {
        val transport = FakeTransport()
        transport.putFault(0x12, byteArrayOf(0x2C, 0x6A, 0x08), 0x09)
        val engine = DiagnosticsEngine(transport)

        assertTrue(engine.readFaults(dme).isNotEmpty())
        engine.clearFaults(dme)
        assertEquals(0, engine.readFaults(dme).size)
    }

    @Test
    fun readLive_decodesWithScaleAndOffset() {
        val transport = FakeTransport()
        transport.putLive(0x12, 0x4600, byteArrayOf(0x89.toByte())) // 137
        val engine = DiagnosticsEngine(transport)
        val coolant = LiveParameter(
            id = "coolant",
            moduleId = "dme",
            name = "Coolant",
            dataIdentifier = 0x4600,
            byteLength = 1,
            scale = 1.0,
            offset = -48.0,
            unit = "°C",
            demoRaw = "89"
        )

        val value = engine.readLive(dme, coolant)
        assertEquals(89.0, value!!, 0.0001)
        assertEquals("89 °C", coolant.format(value))
    }
}
