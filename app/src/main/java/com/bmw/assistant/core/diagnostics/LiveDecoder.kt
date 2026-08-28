package com.bmw.assistant.core.diagnostics

import com.bmw.assistant.data.model.LiveParameter

/** Decodes the raw bytes of a UDS RDBI payload into a physical value for a [LiveParameter]. */
object LiveDecoder {

    /**
     * Reads [LiveParameter.byteLength] bytes at [LiveParameter.byteOffset] big-endian as an
     * unsigned integer, then applies `raw * scale + offset`. Returns null if the payload is
     * too short for the parameter's window.
     */
    fun decode(param: LiveParameter, payload: ByteArray): Double? {
        val end = param.byteOffset + param.byteLength
        if (param.byteOffset < 0 || param.byteLength <= 0 || end > payload.size) return null
        var raw = 0L
        for (i in param.byteOffset until end) {
            raw = (raw shl 8) or (payload[i].toLong() and 0xFF)
        }
        return raw * param.scale + param.offset
    }
}
