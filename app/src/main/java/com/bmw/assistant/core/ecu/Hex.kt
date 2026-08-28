package com.bmw.assistant.core.ecu

/** Byte <-> hex string helpers used across the transport, coding, and diagnostics layers. */
object Hex {
    fun encode(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun encodeCompact(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /** Parses "0x1A", "1A", "1a 2b", "1A2B" etc. into bytes. */
    fun decode(s: String): ByteArray {
        val cleaned = s.trim().removePrefix("0x").removePrefix("0X")
            .replace(" ", "").replace(",", "")
        require(cleaned.length % 2 == 0) { "Hex string must have an even number of digits" }
        return ByteArray(cleaned.length / 2) {
            cleaned.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    fun parseByte(s: String): Int {
        val cleaned = s.trim().removePrefix("0x").removePrefix("0X")
        return cleaned.toInt(16) and 0xFF
    }
}
