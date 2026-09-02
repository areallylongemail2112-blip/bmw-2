package com.bmw.assistant.core.ecu.obd

import com.bmw.assistant.core.ecu.EcuException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the OBD path end to end against a scripted adapter: the setup sequence, single and
 * segmented exchanges, flow control, "response pending", and the adapter error strings that must
 * become readable messages instead of a hang.
 */
class Elm327TransportTest {

    private val zgw = 0x10
    private val frm = 0x72

    /** Builds an ELM response line: 3-digit CAN id followed by the padded data field. */
    private fun line(canId: Int, vararg data: Int): String =
        "%03X".format(canId) + IsoTp.pad(ByteArray(data.size) { data[it].toByte() })
            .joinToString("") { "%02X".format(it) }

    /** The gateway's answer to the connect-time TesterPresent. */
    private fun testerPresentReply() = line(IsoTp.ecuCanId(zgw), 0xF1, 0x02, 0x7E, 0x00)

    /**
     * A connected transport wired to [respond]. The test's responder is consulted first — so a
     * test can script `ATMA` or any other command — and anything it does not answer falls back
     * to a well-behaved adapter: `OK` to setup commands and a gateway that answers TesterPresent.
     */
    private fun connectedTransport(
        respond: (String) -> String?
    ): Pair<Elm327Transport, FakeSerialLink> {
        val link = FakeSerialLink { command ->
            respond(command) ?: when {
                command.isEmpty() -> ""
                command.startsWith("AT") -> if (command == "ATZ") "ELM327 v1.5" else "OK"
                // The connect handshake: TesterPresent to the gateway.
                command == "10023E0000000000" -> testerPresentReply()
                else -> null
            }
        }
        val transport = Elm327Transport(link)
        transport.connect()
        return transport to link
    }

    @Test
    fun connect_configuresTheAdapterForRawExtendedAddressedCan() {
        val (transport, link) = connectedTransport { null }
        transport.disconnect()

        val setup = link.commands.filter { it.startsWith("AT") }
        // Raw CAN with our own ISO-TP, headers visible, adapter flow control off.
        assertTrue("ATCAF0 missing", setup.contains("ATCAF0"))
        assertTrue("ATCFC0 missing", setup.contains("ATCFC0"))
        assertTrue("ATH1 missing", setup.contains("ATH1"))
        assertTrue("ATSP6 missing", setup.contains("ATSP6"))
        // Tester header 0x6F1 and the gateway receive filter 0x610.
        assertTrue("ATSH6F1 missing", setup.contains("ATSH6F1"))
        assertTrue("ATCRA610 missing", setup.contains("ATCRA610"))
        // Echo off must precede the first real exchange or every reply is doubled.
        assertTrue(setup.indexOf("ATE0") < setup.indexOf("ATSH6F1"))
        assertEquals("ELM327 v1.5", transport.adapterIdentity)
    }

    @Test
    fun connect_failsClearlyWhenTheGatewayIsSilent() {
        val link = FakeSerialLink { command ->
            if (command.isEmpty() || command.startsWith("AT")) "OK" else "NO DATA"
        }

        val error = assertThrows(EcuException::class.java) { Elm327Transport(link).connect() }

        assertTrue(error.message!!.contains("No response") || error.message!!.contains("did not answer"))
    }

    @Test
    fun transceive_sendsAPaddedSingleFrameAndReturnsTheAnswer() {
        val (transport, link) = connectedTransport { command ->
            if (command == "7203223000000000") line(IsoTp.ecuCanId(frm), 0xF1, 0x04, 0x62, 0x30, 0x00, 0x01)
            else null
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00, 0x01), response)
        // The request went out as a full 8-byte frame; a short one would be dropped by the module.
        assertTrue(link.commands.contains("7203223000000000"))
        assertTrue(link.commands.contains("ATCRA672"))
    }

    @Test
    fun transceive_ignoresFramesFromOtherModules() {
        val (transport, _) = connectedTransport { command ->
            if (command == "7203223000000000") {
                line(0x660, 0xF1, 0x02, 0x7E, 0x00) + "\r" +
                    line(IsoTp.ecuCanId(frm), 0xF1, 0x03, 0x62, 0x30, 0x09)
            } else null
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x09), response)
    }

    @Test
    fun transceive_reassemblesASegmentedResponseAfterSendingFlowControl() {
        val (transport, link) = connectedTransport { command ->
            when (command) {
                // First frame announcing 10 bytes.
                "7203223000000000" -> line(IsoTp.ecuCanId(frm), 0xF1, 0x10, 0x0A, 0x62, 0x30, 0x00, 1, 2)
                // Our flow control; the module then sends the rest.
                "7230000A00000000" -> line(IsoTp.ecuCanId(frm), 0xF1, 0x21, 3, 4, 5, 6, 7)
                else -> null
            }
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x00, 1, 2, 3, 4, 5, 6, 7), response)
        assertTrue("flow control was never sent", link.commands.contains("7230000A00000000"))
    }

    @Test
    fun transceive_streamsASegmentedRequestAfterTheModulesFlowControl() {
        val request = byteArrayOf(0x2E, 0x30, 0x00) + ByteArray(9) { (it + 1).toByte() }
        val frames = IsoTp.buildFrames(frm, request).map { f -> f.joinToString("") { "%02X".format(it) } }
        val (transport, link) = connectedTransport { command ->
            when (command) {
                frames[0] -> line(IsoTp.ecuCanId(frm), 0xF1, 0x30, 0x00, 0x00)
                frames.last() -> line(IsoTp.ecuCanId(frm), 0xF1, 0x03, 0x6E, 0x30, 0x00)
                else -> null
            }
        }

        val response = transport.transceive(frm, request)
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x6E, 0x30, 0x00), response)
        frames.forEach { assertTrue("frame $it never sent", link.commands.contains(it)) }
        // Replies must be re-enabled; otherwise every later request looks like a silent module.
        assertEquals("ATR1", link.commands.last { it == "ATR0" || it == "ATR1" })
    }

    @Test
    fun transceive_honoursFlowControlBlockSize() {
        // Block size 1: the module wants a fresh flow control after every consecutive frame.
        val request = byteArrayOf(0x2E, 0x30, 0x00) + ByteArray(15) { (it + 1).toByte() }
        val frames = IsoTp.buildFrames(frm, request).map { f -> f.joinToString("") { "%02X".format(it) } }
        var flowControlsSent = 0
        val (transport, _) = connectedTransport { command ->
            when {
                command == frames[0] || (command in frames && command != frames.last()) -> {
                    flowControlsSent++
                    line(IsoTp.ecuCanId(frm), 0xF1, 0x30, 0x01, 0x00)
                }
                command == frames.last() -> line(IsoTp.ecuCanId(frm), 0xF1, 0x03, 0x6E, 0x30, 0x00)
                else -> null
            }
        }

        val response = transport.transceive(frm, request)
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x6E, 0x30, 0x00), response)
        // One after the first frame, then one per completed block.
        assertTrue("block size was ignored", flowControlsSent >= 2)
    }

    @Test
    fun transceive_rejectsAFlowControlOverflow() {
        val request = byteArrayOf(0x2E, 0x30, 0x00) + ByteArray(9)
        val frames = IsoTp.buildFrames(frm, request).map { f -> f.joinToString("") { "%02X".format(it) } }
        val (transport, _) = connectedTransport { command ->
            if (command == frames[0]) line(IsoTp.ecuCanId(frm), 0xF1, 0x32, 0x00, 0x00) else null
        }

        val error = assertThrows(EcuException::class.java) { transport.transceive(frm, request) }
        transport.disconnect()

        assertTrue(error.message!!.contains("rejected the request size"))
    }

    @Test
    fun transceive_keepsTheRealAnswerThatFollowsAResponsePending() {
        // A module commonly sends 0x78 and then the real answer inside the same response window.
        val (transport, link) = connectedTransport { command ->
            if (command == "7203223000000000") {
                line(IsoTp.ecuCanId(frm), 0xF1, 0x03, 0x7F, 0x22, 0x78) + "\r" +
                    line(IsoTp.ecuCanId(frm), 0xF1, 0x03, 0x62, 0x30, 0x42)
            } else null
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x42), response)
        // The answer was already in hand, so there was no need to fall back to monitor mode.
        assertTrue("fell back to ATMA unnecessarily", link.commands.none { it == "ATMA" })
    }

    @Test
    fun transceive_waitsOutAResponsePendingInMonitorMode() {
        val (transport, link) = connectedTransport { command ->
            when (command) {
                "7203223000000000" -> line(IsoTp.ecuCanId(frm), 0xF1, 0x03, 0x7F, 0x22, 0x78)
                "ATMA" -> line(IsoTp.ecuCanId(frm), 0xF1, 0x03, 0x62, 0x30, 0x77)
                else -> null
            }
        }

        val response = transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        transport.disconnect()

        assertArrayEquals(byteArrayOf(0x62, 0x30, 0x77), response)
        assertTrue(link.commands.contains("ATMA"))
    }

    @Test
    fun transceive_turnsAdapterErrorStringsIntoReadableMessages() {
        val cases = mapOf(
            "CAN ERROR" to "CAN bus error",
            "BUFFER FULL" to "buffer overflow",
            "UNABLE TO CONNECT" to "could not join"
        )
        cases.forEach { (adapterSays, expected) ->
            val (transport, _) = connectedTransport { command ->
                if (command == "7203223000000000") adapterSays else null
            }

            val error = assertThrows(EcuException::class.java) {
                transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
            }
            transport.disconnect()

            assertTrue(
                "\"$adapterSays\" produced \"${error.message}\"",
                error.message!!.contains(expected)
            )
        }
    }

    @Test
    fun transceive_reportsASilentModule() {
        val (transport, _) = connectedTransport { command ->
            if (command == "7203223000000000") "NO DATA" else null
        }

        val error = assertThrows(EcuException::class.java) {
            transport.transceive(frm, byteArrayOf(0x22, 0x30, 0x00))
        }
        transport.disconnect()

        assertTrue(error.message!!.contains("No response from module"))
    }

    @Test
    fun transceive_refusesARequestLargerThanIsoTpCanCarry() {
        val (transport, _) = connectedTransport { null }

        val error = assertThrows(EcuException::class.java) {
            transport.transceive(frm, ByteArray(IsoTp.MAX_PAYLOAD + 1))
        }
        transport.disconnect()

        assertTrue(error.message!!.contains("exceeds the ISO-TP limit"))
    }
}
