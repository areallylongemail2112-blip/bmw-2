package com.bmw.assistant.data.model

/**
 * Where a coding "current value" came from. The UI uses this so a cached or
 * factory default is never mistaken for a live ECU read.
 */
enum class ValueSource {
    /** Decoded from a successful UDS read (or demo transport, which is the "car"). */
    FROM_CAR,

    /** Last written / previously stored value; not confirmed against the ECU this session. */
    LOCAL_CACHE,

    /** No stored value — falling back to the JSON default. */
    DEFAULT
}

data class StoredValue(
    val value: String,
    val source: ValueSource
)
