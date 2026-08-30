package com.bmw.assistant.data.model

/**
 * A live measurement value read from a module over UDS ReadDataByIdentifier (0x22).
 *
 * The raw bytes at [byteOffset]..[byteOffset]+[byteLength] are read big-endian as an unsigned
 * integer, then converted to a physical value as `raw * scale + offset`. As with coding maps,
 * the DIDs/scales here are ILLUSTRATIVE (real DIDs vary by ECU and I-level) and drive the demo
 * transport; over ENET they will only return sensible numbers once matched to your car.
 *
 * @param dataIdentifier UDS DID that returns this measurement.
 * @param byteOffset     first byte of the value within the DID payload.
 * @param byteLength     number of bytes (1 or 2 typically), big-endian, unsigned.
 * @param scale          multiply the raw integer by this.
 * @param offset         then add this (e.g. -48 for BMW temperature encodings).
 * @param decimals       digits to show after the decimal point.
 * @param demoRaw        raw hex the demo transport seeds for this DID, e.g. "69" or "1A2B".
 */
data class LiveParameter(
    val id: String,
    val moduleId: String,
    val name: String,
    val description: String = "",
    val dataIdentifier: Int,
    val byteOffset: Int = 0,
    val byteLength: Int = 1,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String? = null,
    val decimals: Int = 0,
    val min: Double? = null,
    val max: Double? = null,
    val demoRaw: String = "00"
) {
    /** Formats a decoded value with its unit, e.g. "89 °C". */
    fun format(value: Double): String {
        val number = if (decimals <= 0) Math.round(value).toString() else "%.${decimals}f".format(value)
        return if (unit.isNullOrBlank()) number else "$number $unit"
    }
}

/**
 * A known fault-code description, keyed by the DTC's high 16 bits as a 4-hex-digit string
 * (e.g. "2C6A"). Used to turn a raw code into a plain-English explanation where we have one.
 */
data class DtcCatalogEntry(
    val code: String,
    val description: String
)

/** A fault the demo transport pretends a module has stored, so diagnostics is usable offline. */
data class DemoFault(
    val moduleId: String,
    val dtc: String,       // 6 hex digits = 3-byte DTC, e.g. "2C6A08"
    val status: Int = 0x09 // testFailed + confirmed
)

/** Root shape of the bundled `diagnostics_f10.json` asset. */
data class DiagnosticsData(
    val assetVersion: Int = 1,
    val liveData: List<LiveParameter> = emptyList(),
    val dtcCatalog: List<DtcCatalogEntry> = emptyList(),
    val demoFaults: List<DemoFault> = emptyList()
)
