package com.bmwf10.coding.ecu

import com.bmwf10.coding.data.model.CodingItem
import com.bmwf10.coding.data.model.EcuMap
import com.bmwf10.coding.data.model.Module
import com.bmwf10.coding.data.model.ValueType

/**
 * Turns a friendly coding value into an ECU write (and reads one back). This is the piece
 * that implements BMW's "edit one byte of the coding block" model:
 *
 *   read block -> modify byte at ecuMap.byteOffset under ecuMap.bitMask -> write block back.
 *
 * SAFETY: on a coding-capable *hardware* transport, [applyCoding] refuses to write unless the
 * coding item carries a `verified` map. The illustrative maps bundled in the JSON asset are
 * for demo only; pushing an unverified byte offset to a real module can brick it. Demo mode
 * bypasses the gate because nothing physical is touched.
 */
class CodingEngine(private val transport: EcuTransport, private val isDemo: Boolean) {

    /**
     * @return the raw byte written (for display/logging).
     * @throws EcuException if not permitted or the write fails.
     */
    fun applyCoding(module: Module, coding: CodingItem, uiValue: String): Byte {
        val map = coding.ecuMap
            ?: throw EcuException("No ECU map defined for \"${coding.name}\" — cannot write.")

        if (!transport.supportsCoding) {
            throw EcuException("The active connection cannot write coding data. Use ENET or demo mode.")
        }
        if (!isDemo && !map.verified) {
            throw EcuException(
                "Coding map for \"${coding.name}\" is not verified for this car. " +
                    "Writing it to a real module is blocked to avoid damage. " +
                    "Provide a verified map (see README) or use demo mode."
            )
        }

        val rawValue = encode(coding, map, uiValue)

        // Read-modify-write the coding block.
        val block = transport.readCodingBlock(module.diagAddress, map.dataIdentifier)
        val working = if (map.byteOffset < block.size) block.copyOf()
        else block.copyOf(map.byteOffset + 1) // grow if the module returned a shorter block

        val existing = working[map.byteOffset].toInt() and 0xFF
        val merged = (existing and map.bitMask.inv()) or (rawValue and map.bitMask)
        working[map.byteOffset] = merged.toByte()

        transport.writeCodingBlock(module.diagAddress, map.dataIdentifier, working)
        return merged.toByte()
    }

    /** Reads the current byte for a coding and decodes it back to a friendly value. */
    fun readCoding(module: Module, coding: CodingItem): String? {
        val map = coding.ecuMap ?: return null
        val block = transport.readCodingBlock(module.diagAddress, map.dataIdentifier)
        if (map.byteOffset >= block.size) return null
        val raw = (block[map.byteOffset].toInt() and map.bitMask) and 0xFF
        return decode(coding, map, raw)
    }

    private fun encode(coding: CodingItem, map: EcuMap, uiValue: String): Int = when (coding.valueType) {
        ValueType.BOOLEAN, ValueType.ENUM -> {
            val encoded = map.encodedValues?.get(uiValue)
                ?: throw EcuException("No byte mapping for value \"$uiValue\"")
            Hex.parseByte(encoded) and map.bitMask
        }
        ValueType.INTEGER -> {
            val n = uiValue.toDoubleOrNull()
                ?: throw EcuException("\"$uiValue\" is not a number")
            // Shift the numeric value into the field's bit position before masking, so a
            // field packed above bit 0 (e.g. bitMask 0xF0) is encoded correctly.
            ((Math.round(n / map.scale).toInt() shl map.bitShift()) and map.bitMask)
        }
        ValueType.HEX -> Hex.parseByte(uiValue) and map.bitMask
    }

    /** Number of low bits the field is shifted up by (0 for a bit-0-aligned or 0xFF mask). */
    private fun EcuMap.bitShift(): Int =
        if (bitMask == 0) 0 else Integer.numberOfTrailingZeros(bitMask)

    private fun decode(coding: CodingItem, map: EcuMap, raw: Int): String = when (coding.valueType) {
        ValueType.BOOLEAN, ValueType.ENUM -> {
            map.encodedValues?.entries
                ?.firstOrNull { (Hex.parseByte(it.value) and map.bitMask) == raw }
                ?.key ?: "0x%02X".format(raw)
        }
        ValueType.INTEGER -> Math.round((raw shr map.bitShift()) * map.scale).toString()
        ValueType.HEX -> "0x%02X".format(raw)
    }
}
