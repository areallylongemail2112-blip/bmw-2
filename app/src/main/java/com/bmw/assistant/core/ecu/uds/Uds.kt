package com.bmw.assistant.core.ecu.uds

/**
 * UDS (ISO 14229) service IDs and small request/response helpers used across the app for BMW
 * F-series **coding** and **diagnostics**.
 *
 * Coding a byte in a module is, at the protocol level:
 *   1. DiagnosticSessionControl -> extended session (0x10 0x03)
 *   2. ReadDataByIdentifier of the coding block (0x22 <DID>)   -> current bytes
 *   3. edit one byte locally
 *   4. (SecurityAccess if the module requires it: 0x27)
 *   5. WriteDataByIdentifier of the modified block (0x2E <DID> <bytes>)
 *
 * Diagnostics reuse the same request/response machinery:
 *   - ReadDTCInformation (0x19 0x02 <mask>) to list stored fault codes,
 *   - ClearDiagnosticInformation (0x14 <group>) to erase them,
 *   - ReadDataByIdentifier (0x22 <DID>) to read a live measurement value.
 *
 * This object holds only the constants and byte builders/parsers. Moving the bytes to the ECU
 * (over DoIP or an OBD adapter) is the transport's job; sequencing sessions and checking
 * responses is [com.bmw.assistant.core.ecu.UdsClient]'s.
 */
object Uds {
    // --- request service IDs ---
    const val SID_DIAGNOSTIC_SESSION_CONTROL = 0x10
    const val SID_ECU_RESET = 0x11
    const val SID_CLEAR_DIAGNOSTIC_INFORMATION = 0x14
    const val SID_READ_DTC_INFORMATION = 0x19
    const val SID_READ_DATA_BY_IDENTIFIER = 0x22
    const val SID_SECURITY_ACCESS = 0x27
    const val SID_WRITE_DATA_BY_IDENTIFIER = 0x2E
    const val SID_TESTER_PRESENT = 0x3E

    // --- session types ---
    const val SESSION_DEFAULT = 0x01
    const val SESSION_EXTENDED = 0x03

    // --- ReadDTCInformation sub-functions ---
    const val DTC_REPORT_BY_STATUS_MASK = 0x02

    /** Report every DTC regardless of its status bits. */
    const val DTC_STATUS_MASK_ALL = 0xFF

    /** Group-of-DTC value that clears all fault memory. */
    const val DTC_GROUP_ALL = 0xFFFFFF

    // positive response = requestSID + 0x40
    const val POSITIVE_RESPONSE_OFFSET = 0x40
    const val NEGATIVE_RESPONSE = 0x7F

    fun sessionControl(session: Int = SESSION_EXTENDED): ByteArray =
        byteArrayOf(SID_DIAGNOSTIC_SESSION_CONTROL.toByte(), session.toByte())

    fun testerPresent(): ByteArray =
        byteArrayOf(SID_TESTER_PRESENT.toByte(), 0x00)

    fun readDataByIdentifier(did: Int): ByteArray =
        byteArrayOf(SID_READ_DATA_BY_IDENTIFIER.toByte(), (did shr 8).toByte(), did.toByte())

    fun writeDataByIdentifier(did: Int, data: ByteArray): ByteArray =
        byteArrayOf(SID_WRITE_DATA_BY_IDENTIFIER.toByte(), (did shr 8).toByte(), did.toByte()) + data

    /** ReadDTCInformation, sub-function reportDTCByStatusMask. */
    fun readDtcByStatusMask(statusMask: Int = DTC_STATUS_MASK_ALL): ByteArray =
        byteArrayOf(
            SID_READ_DTC_INFORMATION.toByte(),
            DTC_REPORT_BY_STATUS_MASK.toByte(),
            statusMask.toByte()
        )

    /** ClearDiagnosticInformation for a 3-byte group-of-DTC (0xFFFFFF = all). */
    fun clearDiagnosticInformation(group: Int = DTC_GROUP_ALL): ByteArray =
        byteArrayOf(
            SID_CLEAR_DIAGNOSTIC_INFORMATION.toByte(),
            (group shr 16).toByte(),
            (group shr 8).toByte(),
            group.toByte()
        )

    /** True if [response] is the positive reply to [requestSid]. */
    fun isPositive(response: ByteArray, requestSid: Int): Boolean =
        response.isNotEmpty() &&
            (response[0].toInt() and 0xFF) == (requestSid + POSITIVE_RESPONSE_OFFSET)

    /** Extracts the negative-response code, or null if the reply is not negative. */
    fun negativeResponseCode(response: ByteArray): Int? =
        if (response.size >= 3 && (response[0].toInt() and 0xFF) == NEGATIVE_RESPONSE)
            response[2].toInt() and 0xFF else null

    fun describeNrc(nrc: Int): String = when (nrc) {
        0x10 -> "General reject"
        0x11 -> "Service not supported"
        0x12 -> "Sub-function not supported"
        0x13 -> "Incorrect message length"
        0x22 -> "Conditions not correct"
        0x31 -> "Request out of range"
        0x33 -> "Security access denied"
        0x35 -> "Invalid key"
        0x78 -> "Response pending"
        0x7E -> "Sub-function not supported in active session"
        0x7F -> "Service not supported in active session"
        else -> "NRC 0x${nrc.toString(16).uppercase()}"
    }
}
