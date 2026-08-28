package com.bmw.assistant.data.model

import com.google.gson.annotations.SerializedName

/**
 * The kind of input control a coding item needs, which also drives how its value
 * is validated and how it is encoded into an ECU coding byte.
 */
enum class ValueType {
    @SerializedName("boolean") BOOLEAN,
    @SerializedName("enum") ENUM,
    @SerializedName("integer") INTEGER,
    @SerializedName("hex") HEX
}
