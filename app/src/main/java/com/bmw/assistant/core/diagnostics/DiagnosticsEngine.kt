package com.bmw.assistant.core.diagnostics

import com.bmw.assistant.core.ecu.EcuException
import com.bmw.assistant.core.ecu.EcuTransport
import com.bmw.assistant.core.ecu.UdsClient
import com.bmw.assistant.core.ecu.uds.RawDtc
import com.bmw.assistant.data.model.LiveParameter
import com.bmw.assistant.data.model.Module

/**
 * Reads what the car is doing (BimmerLink-style), the read-only counterpart to
 * [com.bmw.assistant.core.coding.CodingEngine]. Fault codes and live values both ride the
 * shared [UdsClient], so nothing here re-implements session or negative-response handling.
 *
 * Clearing fault memory is a standard, reversible operation (the codes come back if the fault
 * is still present), so — unlike coding — it is allowed on real hardware without a verified
 * map. The UI still confirms before clearing.
 */
class DiagnosticsEngine(transport: EcuTransport) {

    private val uds = UdsClient(transport)

    /** All stored fault codes for [module]. */
    fun readFaults(module: Module): List<RawDtc> =
        uds.readDtcsByStatusMask(module.diagAddress)

    /** Erases [module]'s fault memory (UDS ClearDiagnosticInformation). */
    fun clearFaults(module: Module) =
        uds.clearDtcs(module.diagAddress)

    /**
     * Opens the extended diagnostic session on [module] once. Call this before a batch of
     * [readLive] reads with `openSession = false` so the session isn't renegotiated per value.
     */
    fun openSession(module: Module) = uds.openExtendedSession(module.diagAddress)

    /**
     * Reads and decodes one live parameter. Returns null if the module's payload was too short
     * to contain the value. Pass [openSession] = false when the caller has already opened the
     * session for a batch of reads (avoids a session-control round-trip per parameter).
     * @throws EcuException on a link error or negative response.
     */
    fun readLive(module: Module, param: LiveParameter, openSession: Boolean = true): Double? {
        val payload = uds.readDataByIdentifier(module.diagAddress, param.dataIdentifier, openSession)
        return LiveDecoder.decode(param, payload)
    }
}
