package com.bmw.assistant.core.ecu.uds

/**
 * One Diagnostic Trouble Code as reported by UDS ReadDTCInformation (0x19 0x02): a 3-byte
 * DTC number plus a 1-byte status. The [status] bits follow ISO 14229 Annex D (bit 0 =
 * testFailed, bit 3 = confirmedDTC, ...).
 *
 * BMW modules number their faults in their own space, so the most reliable label is the raw
 * hex code ([hexCode]). For familiarity we also render the ISO 15031-6 / SAE J2012 form
 * ([saeCode], e.g. "P1520") derived from the first two bytes.
 */
data class RawDtc(val code: ByteArray, val status: Int) {

    init {
        require(code.size == 3) { "A UDS DTC is 3 bytes, got ${code.size}" }
    }

    /** The high 16 bits — what a BMW fault table is usually keyed on. */
    val high16: Int get() = ((code[0].toInt() and 0xFF) shl 8) or (code[1].toInt() and 0xFF)

    /** Full 24-bit code as hex, e.g. "2C6A08". */
    fun hexCode(): String =
        "%02X%02X%02X".format(
            code[0].toInt() and 0xFF,
            code[1].toInt() and 0xFF,
            code[2].toInt() and 0xFF
        )

    /** SAE J2012 5-character code (letter + 4 hex nibbles) derived from the first two bytes. */
    fun saeCode(): String {
        val b0 = code[0].toInt() and 0xFF
        val b1 = code[1].toInt() and 0xFF
        val letter = charArrayOf('P', 'C', 'B', 'U')[(b0 shr 6) and 0x03]
        val d1 = (b0 shr 4) and 0x03
        val d2 = b0 and 0x0F
        val d3 = (b1 shr 4) and 0x0F
        val d4 = b1 and 0x0F
        return "%c%d%X%X%X".format(letter, d1, d2, d3, d4)
    }

    val isTestFailed: Boolean get() = status and 0x01 != 0
    val isConfirmed: Boolean get() = status and 0x08 != 0
    val isPending: Boolean get() = status and 0x04 != 0

    /** A short human status: what the driver actually cares about. */
    fun statusLabel(): String = when {
        isTestFailed -> "Active"
        isConfirmed -> "Stored"
        isPending -> "Pending"
        else -> "Cleared / historic"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawDtc) return false
        return code.contentEquals(other.code) && status == other.status
    }

    override fun hashCode(): Int = code.contentHashCode() * 31 + status
}

object Dtc {

    /**
     * Parses the payload of a positive ReadDTCInformation-by-status-mask response.
     * Layout: [0x59][0x02][statusAvailabilityMask][ DTC(3) status(1) ]*.
     * All-zero DTC records (padding) are dropped.
     */
    fun parseByStatusMask(response: ByteArray): List<RawDtc> {
        if (response.size < 3) return emptyList()
        val records = response.copyOfRange(3, response.size)
        val out = ArrayList<RawDtc>()
        var i = 0
        while (i + 4 <= records.size) {
            val code = records.copyOfRange(i, i + 3)
            val status = records[i + 3].toInt() and 0xFF
            if (!code.all { it.toInt() == 0 }) out.add(RawDtc(code, status))
            i += 4
        }
        return out
    }
}
