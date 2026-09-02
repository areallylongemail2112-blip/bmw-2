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
            throw EcuException("Could not open extended session: " + describe(resp))
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
            throw EcuException("ReadDataByIdentifier(0x%04X) failed: ".format(did) + describe(resp))
        }
        requireEchoedIdentifier(resp, did, "ReadDataByIdentifier")
        return if (resp.size > 3) resp.copyOfRange(3, resp.size) else ByteArray(0)
    }

    /** WriteDataByIdentifier: writes [data] to coding/config block [did]. */
    fun writeDataByIdentifier(diagAddress: Int, did: Int, data: ByteArray, openSession: Boolean = true) {
        if (openSession) openExtendedSession(diagAddress)
        val request = Uds.writeDataByIdentifier(did, data)
        if (request.size > transport.maxRequestLength) {
            throw EcuException(
                "Coding block is ${data.size} bytes, which this connection cannot carry " +
                    "(max ${transport.maxRequestLength} bytes per request). " +
                    "Use an ENET cable or an STN-chip adapter."
            )
        }
        val resp = transport.transceive(diagAddress, request)
        if (!Uds.isPositive(resp, Uds.SID_WRITE_DATA_BY_IDENTIFIER)) {
            throw EcuException("WriteDataByIdentifier(0x%04X) failed: ".format(did) + describe(resp))
        }
        requireEchoedIdentifier(resp, did, "WriteDataByIdentifier")
    }

    /**
     * A 0x62/0x6E response echoes the identifier it refers to. Checking it is the last line of
     * defence against a stale answer being paired with the wrong request: for coding that would
     * mean reading one block and writing its bytes into a different one.
     */
    private fun requireEchoedIdentifier(response: ByteArray, did: Int, service: String) {
        if (response.size < 3) throw EcuException("$service(0x%04X) returned a truncated response".format(did))
        val echoed = ((response[1].toInt() and 0xFF) shl 8) or (response[2].toInt() and 0xFF)
        if (echoed != did) {
            throw EcuException(
                "$service(0x%04X) answered for identifier 0x%04X — response mismatch, aborting".format(did, echoed)
            )
        }
    }

    /**
     * Soft-reset the module so newly written coding is applied. Best-effort: some modules
     * ignore 0x11, which is not a coding failure.
     */
    fun ecuReset(diagAddress: Int, resetType: Int = Uds.RESET_SOFT) {
        val resp = transport.transceive(diagAddress, Uds.ecuReset(resetType))
        if (!Uds.isPositive(resp, Uds.SID_ECU_RESET)) {
            throw EcuException("ECUReset failed: " + describe(resp))
        }
    }

    /** ReadDTCInformation by status mask (0x19 0x02): the module's stored fault codes. */
    fun readDtcsByStatusMask(diagAddress: Int, statusMask: Int = Uds.DTC_STATUS_MASK_ALL): List<RawDtc> {
        openExtendedSession(diagAddress)
        val resp = transport.transceive(diagAddress, Uds.readDtcByStatusMask(statusMask))
        if (!Uds.isPositive(resp, Uds.SID_READ_DTC_INFORMATION)) {
            throw EcuException("ReadDTCInformation failed: " + describe(resp))
        }
        return Dtc.parseByStatusMask(resp)
    }

    /** ClearDiagnosticInformation (0x14): erases the module's fault memory. */
    fun clearDtcs(diagAddress: Int, group: Int = Uds.DTC_GROUP_ALL) {
        openExtendedSession(diagAddress)
        val resp = transport.transceive(diagAddress, Uds.clearDiagnosticInformation(group))
        if (!Uds.isPositive(resp, Uds.SID_CLEAR_DIAGNOSTIC_INFORMATION)) {
            throw EcuException("ClearDiagnosticInformation failed: " + describe(resp))
        }
    }

    private fun describe(resp: ByteArray): String =
        Uds.negativeResponseCode(resp)?.let { Uds.describeNrc(it) }
            ?: ("unexpected response " + Hex.encode(resp))
}
