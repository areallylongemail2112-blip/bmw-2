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
        validateMap(map)

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

        // Read-modify-write the coding block. Never grow a short block — that would write
        // past the ECU's defined coding length and can be rejected (or worse).
        val block = transport.readCodingBlock(module.diagAddress, map.dataIdentifier)
        if (map.byteOffset >= block.size) {
            throw EcuException(
                "Coding offset ${map.byteOffset} is outside the module's coding block " +
                    "(${block.size} bytes). Refusing to write."
            )
        }

        val working = block.copyOf()
        val existing = working[map.byteOffset].toInt() and 0xFF
        val merged = (existing and map.bitMask.inv()) or (rawValue and map.bitMask)
        working[map.byteOffset] = merged.toByte()

        transport.writeCodingBlock(module.diagAddress, map.dataIdentifier, working)
        return merged.toByte()
    }

    /** Reads the current byte for a coding and decodes it back to a friendly value. */
    fun readCoding(module: Module, coding: CodingItem): String? {
        val map = coding.ecuMap ?: return null
        if (map.bitMask == 0 || map.byteOffset < 0) return null
        val block = transport.readCodingBlock(module.diagAddress, map.dataIdentifier)
        if (map.byteOffset >= block.size) return null
        val masked = (block[map.byteOffset].toInt() and 0xFF) and map.bitMask
        return decode(coding, map, masked)
    }

    private fun validateMap(map: EcuMap) {
        if (map.byteOffset < 0) throw EcuException("Invalid coding byteOffset ${map.byteOffset}")
        if (map.bitMask == 0) throw EcuException("Invalid coding bitMask 0")
        if (map.scale <= 0.0) throw EcuException("Invalid coding scale ${map.scale}")
    }

    /** Lowest set bit index in [mask] — how far an integer/hex field is shifted in the byte. */
    private fun bitShift(mask: Int): Int = Integer.numberOfTrailingZeros(mask and 0xFF)

    private fun encode(coding: CodingItem, map: EcuMap, uiValue: String): Int = when (coding.valueType) {
        // encodedValues are already positioned under the mask (see EcuMap docs).
        ValueType.BOOLEAN, ValueType.ENUM -> {
            val encoded = map.encodedValues?.get(uiValue)
                ?: throw EcuException("No byte mapping for value \"$uiValue\"")
            Hex.parseByte(encoded) and map.bitMask
        }
        ValueType.INTEGER -> {
            val n = uiValue.toDoubleOrNull()
                ?: throw EcuException("\"$uiValue\" is not a number")
            val shift = bitShift(map.bitMask)
            val field = Math.round(n / map.scale).toInt()
            val maxField = map.bitMask ushr shift
            if (field < 0 || field > maxField) {
                throw EcuException(
                    "Value $uiValue encodes to $field, outside bit field 0..$maxField"
                )
            }
            (field shl shift) and map.bitMask
        }
        ValueType.HEX -> {
            val cleaned = uiValue.trim().removePrefix("0x").removePrefix("0X")
            val expectedDigits = coding.hexLength ?: 2
            if (cleaned.isEmpty()) throw EcuException("Enter a hex value")
            if (cleaned.length > expectedDigits) {
                throw EcuException("Hex value must be at most $expectedDigits digits")
            }
            // Single coding-byte maps only; multi-byte hex needs a wider write path.
            if (expectedDigits > 2) {
                throw EcuException(
                    "Multi-byte hex coding is not supported yet (hexLength=$expectedDigits)"
                )
            }
            val shift = bitShift(map.bitMask)
            val field = Hex.parseByte(uiValue)
            val maxField = map.bitMask ushr shift
            if (field > maxField) {
                throw EcuException("Hex value 0x${cleaned.uppercase()} exceeds bit field mask")
            }
            (field shl shift) and map.bitMask
        }
    }

    private fun decode(coding: CodingItem, map: EcuMap, masked: Int): String = when (coding.valueType) {
        ValueType.BOOLEAN, ValueType.ENUM -> {
            map.encodedValues?.entries
                ?.firstOrNull { (Hex.parseByte(it.value) and map.bitMask) == masked }
                ?.key ?: "0x%02X".format(masked)
        }
        ValueType.INTEGER -> {
            val field = masked ushr bitShift(map.bitMask)
            Math.round(field * map.scale).toString()
        }
        ValueType.HEX -> {
            val field = masked ushr bitShift(map.bitMask)
            "0x%02X".format(field)
        }
    }
}
