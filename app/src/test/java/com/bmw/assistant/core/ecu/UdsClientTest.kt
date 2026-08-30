package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Uds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UdsClientTest {

    @Test
    fun testerPresent_countsOnTransport() {
        val t = FakeTransport()
        UdsClient(t).testerPresent(0x10)
        assertEquals(1, t.testerPresentCount.get())
    }

    @Test
    fun negativeResponse_includesNrc() {
        val t = FakeTransport()
        t.unreachableAddresses.add(0x40)
        val ex = assertThrows(EcuException::class.java) {
            UdsClient(t).openExtendedSession(0x40)
        }
        assertEquals(0x11, ex.nrc)
        assertTrue(ex.message!!.contains("not supported"))
    }

    @Test
    fun unlockSecurity_xorProvider() {
        val t = FakeTransport()
        val seed = UdsClient(t).unlockSecurity(0x72, XorSecurityKeyProvider)
        assertArrayEquals(t.seed, seed)
    }

    @Test
    fun unlockSecurity_invalidKey_throws() {
        val t = FakeTransport()
        val bad = SecurityKeyProvider { _, _, seed -> ByteArray(seed.size) { 0 } }
        val ex = assertThrows(EcuException::class.java) {
            UdsClient(t).unlockSecurity(0x72, bad)
        }
        assertEquals(0x35, ex.nrc)
    }

    @Test
    fun startRoutine_recordsId() {
        val t = FakeTransport()
        UdsClient(t).startRoutine(0x60, 0xAB02)
        assertEquals(0x60 to 0xAB02, t.lastRoutines.single())
    }

    @Test
    fun securityAndTesterPresent_builders() {
        assertArrayEquals(
            byteArrayOf(Uds.SID_SECURITY_ACCESS.toByte(), 0x01),
            Uds.securityAccessRequestSeed()
        )
        assertArrayEquals(
            byteArrayOf(Uds.SID_TESTER_PRESENT.toByte(), 0x00),
            Uds.testerPresent()
        )
        val routine = Uds.routineControl(Uds.ROUTINE_START, 0xAB01, byteArrayOf(0x01))
        assertEquals(Uds.SID_ROUTINE_CONTROL, routine[0].toInt() and 0xFF)
        assertEquals(0xAB01, ((routine[2].toInt() and 0xFF) shl 8) or (routine[3].toInt() and 0xFF))
    }
}
