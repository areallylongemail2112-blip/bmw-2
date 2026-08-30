package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Dtc
import com.bmw.assistant.core.ecu.uds.RawDtc
import com.bmw.assistant.core.ecu.uds.Uds

/**
 * Protocol-level UDS operations on top of a raw [EcuTransport]. This is the single place that
 * knows how to sequence a session, issue a service, and turn a negative response into a
 * readable [EcuException]. Both [com.bmw.assistant.core.coding.CodingEngine] and
 * [com.bmw.assistant.core.diagnostics.DiagnosticsEngine] are thin wrappers over it, so the
 * request/response handling is written and tested once.
 *
 * Every call is blocking; run it off the main thread.
 */
class UdsClient(private val transport: EcuTransport) {

    /** Moves the module into the extended diagnostic session (0x10 0x03). */
    fun openExtendedSession(diagAddress: Int) {
        val resp = transport.transceive(diagAddress, Uds.sessionControl(Uds.SESSION_EXTENDED))
        if (!Uds.isPositive(resp, Uds.SID_DIAGNOSTIC_SESSION_CONTROL)) {
            throw fail("Could not open extended session", resp)
        }
    }

    /** TesterPresent (0x3E) — keeps an extended session alive. */
    fun testerPresent(diagAddress: Int) {
        val resp = transport.transceive(diagAddress, Uds.testerPresent())
        if (!Uds.isPositive(resp, Uds.SID_TESTER_PRESENT)) {
            throw fail("TesterPresent failed", resp)
        }
    }

    /**
     * ReadDataByIdentifier: returns the data bytes of [did] with the echoed
     * [SID+0x40][DID hi][DID lo] header stripped.
     */
    fun readDataByIdentifier(diagAddress: Int, did: Int, openSession: Boolean = true): ByteArray {
        if (openSession) openExtendedSession(diagAddress)
        val resp = transport.transceive(diagAddress, Uds.readDataByIdentifier(did))
        if (!Uds.isPositive(resp, Uds.SID_READ_DATA_BY_IDENTIFIER)) {
            throw fail("ReadDataByIdentifier(0x%04X) failed".format(did), resp)
        }
        return if (resp.size > 3) resp.copyOfRange(3, resp.size) else ByteArray(0)
    }

    /** WriteDataByIdentifier: writes [data] to coding/config block [did]. */
    fun writeDataByIdentifier(diagAddress: Int, did: Int, data: ByteArray, openSession: Boolean = true) {
        if (openSession) openExtendedSession(diagAddress)
        val resp = transport.transceive(diagAddress, Uds.writeDataByIdentifier(did, data))
        if (!Uds.isPositive(resp, Uds.SID_WRITE_DATA_BY_IDENTIFIER)) {
            throw fail("WriteDataByIdentifier(0x%04X) failed".format(did), resp)
        }
    }

    /**
     * SecurityAccess (0x27): request a seed at [seedLevel], derive a key via [provider],
     * then send the key at [seedLevel] + 1. Returns the seed that was unlocked.
     */
    fun unlockSecurity(
        diagAddress: Int,
        provider: SecurityKeyProvider,
        seedLevel: Int = Uds.SECURITY_REQUEST_SEED,
        openSession: Boolean = true
    ): ByteArray {
        if (openSession) openExtendedSession(diagAddress)
        val seedResp = transport.transceive(diagAddress, Uds.securityAccessRequestSeed(seedLevel))
        if (!Uds.isPositive(seedResp, Uds.SID_SECURITY_ACCESS)) {
            throw fail("SecurityAccess requestSeed failed", seedResp)
        }
        val seed = if (seedResp.size > 2) seedResp.copyOfRange(2, seedResp.size) else ByteArray(0)
        if (seed.isEmpty()) throw EcuException("SecurityAccess returned an empty seed")
        val key = provider.keyFor(diagAddress, seedLevel, seed)
            ?: throw EcuException(
                "No seed-to-key function is registered for this module. " +
                    "This app does not ship a BMW SecurityAccess algorithm; " +
                    "register a SecurityKeyProvider or stay in demo mode."
            )
        val keyLevel = seedLevel + 1
        val keyResp = transport.transceive(diagAddress, Uds.securityAccessSendKey(keyLevel, key))
        if (!Uds.isPositive(keyResp, Uds.SID_SECURITY_ACCESS)) {
            throw fail("SecurityAccess sendKey failed", keyResp)
        }
        return seed
    }

    /** RoutineControl startRoutine (0x31 0x01). Returns the option-record payload after the header. */
    fun startRoutine(
        diagAddress: Int,
        routineId: Int,
        optionRecord: ByteArray = byteArrayOf(),
        openSession: Boolean = true
    ): ByteArray {
        if (openSession) openExtendedSession(diagAddress)
        val resp = transport.transceive(
            diagAddress,
            Uds.routineControl(Uds.ROUTINE_START, routineId, optionRecord)
        )
        if (!Uds.isPositive(resp, Uds.SID_ROUTINE_CONTROL)) {
            throw fail("RoutineControl(0x%04X) failed".format(routineId), resp)
        }
        return if (resp.size > 4) resp.copyOfRange(4, resp.size) else ByteArray(0)
    }

    /** ReadDTCInformation by status mask (0x19 0x02): the module's stored fault codes. */
    fun readDtcsByStatusMask(diagAddress: Int, statusMask: Int = Uds.DTC_STATUS_MASK_ALL): List<RawDtc> {
        openExtendedSession(diagAddress)
        val resp = transport.transceive(diagAddress, Uds.readDtcByStatusMask(statusMask))
        if (!Uds.isPositive(resp, Uds.SID_READ_DTC_INFORMATION)) {
            throw fail("ReadDTCInformation failed", resp)
        }
        return Dtc.parseByStatusMask(resp)
    }

    /** ClearDiagnosticInformation (0x14): erases the module's fault memory. */
    fun clearDtcs(diagAddress: Int, group: Int = Uds.DTC_GROUP_ALL) {
        openExtendedSession(diagAddress)
        val resp = transport.transceive(diagAddress, Uds.clearDiagnosticInformation(group))
        if (!Uds.isPositive(resp, Uds.SID_CLEAR_DIAGNOSTIC_INFORMATION)) {
            throw fail("ClearDiagnosticInformation failed", resp)
        }
    }

    private fun fail(prefix: String, resp: ByteArray): EcuException {
        val nrc = Uds.negativeResponseCode(resp)
        val detail = nrc?.let { Uds.describeNrc(it) } ?: ("unexpected response " + Hex.encode(resp))
        return EcuException("$prefix: $detail", nrc = nrc)
    }
}
