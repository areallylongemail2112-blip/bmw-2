package com.bmwf10.coding.data.model

/**
 * Describes HOW a coding value is physically encoded inside an ECU's coding data
 * (the NCD / CAFD "coding string"). This is the bridge between a friendly value in
 * the UI and the raw byte that gets written to the module.
 *
 * BMW F-series coding works by editing bytes of a module's coding data block. A single
 * feature is usually one byte, and often a nibble/bit-field within that byte. The write
 * path is a UDS ReadDataByIdentifier (to fetch the current block), a byte edit at
 * [byteOffset] under [bitMask], then WriteDataByIdentifier of the modified block.
 *
 * IMPORTANT — the offsets/masks/values shipped in the bundled JSON are illustrative and
 * intended for the DEMO transport. Writing to a real ECU requires coding maps verified
 * against that specific car's coding data (its FA/VO + I-level). See the README and the
 * knowledge base doc. [CodingEngine] refuses to build a real write frame unless [verified]
 * is true, so fabricated maps can never be pushed to hardware by accident.
 *
 * @param dataIdentifier  UDS DID (RDBI/WDBI identifier) of the coding block, e.g. 0x3000.
 * @param byteOffset      index of the byte within the coding block that holds this feature.
 * @param bitMask         bits within that byte owned by this feature (0xFF = whole byte).
 * @param encodedValues   for BOOLEAN/ENUM: friendly value -> raw byte value (already shifted
 *                        into the masked bits). e.g. {"true":"0x01","false":"0x00"}.
 * @param scale           for INTEGER: raw = round(uiValue / scale). Defaults to 1.
 * @param verified        true only when the map has been confirmed against real coding data.
 */
data class EcuMap(
    val dataIdentifier: Int = 0,
    val byteOffset: Int = 0,
    val bitMask: Int = 0xFF,
    val encodedValues: Map<String, String>? = null,
    val scale: Double = 1.0,
    val verified: Boolean = false
)
