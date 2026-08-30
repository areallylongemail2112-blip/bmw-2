package com.bmw.assistant.core

import com.bmw.assistant.core.coding.CodingEngine
import com.bmw.assistant.core.diagnostics.DiagnosticsEngine
import com.bmw.assistant.core.ecu.DemoTransport
import com.bmw.assistant.core.ecu.UdsClient
import com.bmw.assistant.core.ecu.XorSecurityKeyProvider
import com.bmw.assistant.core.ecu.uds.Uds
import com.bmw.assistant.core.service.ServiceEngine
import com.bmw.assistant.data.model.CodingItem
import com.bmw.assistant.data.model.EcuMap
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.data.model.ServiceFunction
import com.bmw.assistant.data.model.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end demo path: connect, identify, read/write coding, backup/restore, service. */
class DemoFlowTest {

    private val frm = Module(
        id = "frm", name = "FRM", fullName = "Footwell",
        description = "t", iconName = "zap", diagAddress = 0x72
    )

    @Test
    fun demoSession_identifyReadWriteRestoreAndService() {
        val transport = DemoTransport()
        transport.connect()

        val uds = UdsClient(transport)
        val vin = String(uds.readDataByIdentifier(0x72, Uds.DID_VIN), Charsets.US_ASCII)
        assertEquals(DemoTransport.DEMO_VIN, vin)
        val iLevel = String(uds.readDataByIdentifier(0x72, Uds.DID_I_LEVEL, openSession = false), Charsets.US_ASCII)
        assertEquals(DemoTransport.DEMO_I_LEVEL, iLevel)

        transport.seedCodingByte(0x72, 0x3000, 0, 0x00, 0x01)
        val engine = CodingEngine(transport, isDemo = true, keyProvider = XorSecurityKeyProvider)
        val item = CodingItem(
            id = "frm_cornering_lights",
            moduleId = "frm",
            name = "Cornering Lights",
            description = "d",
            longDescription = "ld",
            valueType = ValueType.BOOLEAN,
            defaultValue = "false",
            safeDefault = "false",
            ecuMap = EcuMap(
                dataIdentifier = 0x3000,
                byteOffset = 0,
                bitMask = 1,
                encodedValues = mapOf("true" to "0x01", "false" to "0x00"),
                verified = false
            )
        )
        assertEquals("false", engine.readCoding(frm, item))
        val backup = engine.readBlock(frm, 0x3000)
        engine.applyCoding(frm, item, "true")
        assertEquals("true", engine.readCoding(frm, item))
        engine.restoreBlock(frm, 0x3000, backup)
        assertEquals("false", engine.readCoding(frm, item))

        DiagnosticsEngine(transport).readFaults(frm)

        ServiceEngine(transport, isDemo = true).run(
            frm.copy(id = "kombi", diagAddress = 0x60),
            ServiceFunction(
                id = "cbs_oil_reset", moduleId = "kombi", name = "Oil",
                description = "d", routineId = 0xAB02
            )
        )
        assertTrue(transport.isConnected)
        transport.disconnect()
    }
}
