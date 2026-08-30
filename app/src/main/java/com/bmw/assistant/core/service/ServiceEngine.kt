package com.bmw.assistant.core.service

import com.bmw.assistant.core.ecu.EcuException
import com.bmw.assistant.core.ecu.EcuTransport
import com.bmw.assistant.core.ecu.Hex
import com.bmw.assistant.core.ecu.UdsClient
import com.bmw.assistant.data.model.Module
import com.bmw.assistant.data.model.ServiceFunction

/**
 * Runs a data-driven service function (CBS reset, battery registration, …)
 * as UDS RoutineControl. Hardware writes require [ServiceFunction.verified],
 * matching the coding-map safety gate.
 */
class ServiceEngine(private val transport: EcuTransport, private val isDemo: Boolean) {

    private val uds = UdsClient(transport)

    fun run(module: Module, service: ServiceFunction): ByteArray {
        if (!transport.supportsDiagnostics) {
            throw EcuException("The active connection cannot run service functions. Use ENET or demo mode.")
        }
        if (!isDemo && !service.verified) {
            throw EcuException(
                "Service “${service.name}” is not verified for this car. " +
                    "Running an unverified routine on a real module is blocked. " +
                    "Use demo mode, or supply a verified routine definition."
            )
        }
        val payload = service.payloadHex?.let { Hex.decode(it) } ?: byteArrayOf()
        return uds.startRoutine(module.diagAddress, service.routineId, payload)
    }
}
