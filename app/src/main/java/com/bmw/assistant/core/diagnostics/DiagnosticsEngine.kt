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
     * Reads and decodes one live parameter. Returns null if the module's payload was too short
     * to contain the value.
     * @throws EcuException on a link error or negative response.
     */
    fun readLive(module: Module, param: LiveParameter): Double? {
        val payload = uds.readDataByIdentifier(module.diagAddress, param.dataIdentifier)
        return LiveDecoder.decode(param, payload)
    }
}
