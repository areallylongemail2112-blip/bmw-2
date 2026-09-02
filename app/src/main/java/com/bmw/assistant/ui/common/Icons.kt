package com.bmw.assistant.ui.common

import com.bmw.assistant.R

/** Maps the JSON `iconName` values to bundled vector drawables. */
object Icons {
    fun drawableFor(name: String): Int = when (name) {
        "zap" -> R.drawable.ic_zap
        "activity" -> R.drawable.ic_activity
        "monitor" -> R.drawable.ic_monitor
        "eye" -> R.drawable.ic_eye
        "key" -> R.drawable.ic_key
        "engine" -> R.drawable.ic_engine
        "speaker" -> R.drawable.ic_speaker
        "seatbelt" -> R.drawable.ic_seatbelt
        "snowflake" -> R.drawable.ic_snowflake
        "camera" -> R.drawable.ic_camera
        "gears" -> R.drawable.ic_gears
        "mirror" -> R.drawable.ic_mirror
        "play" -> R.drawable.ic_play
        "steering" -> R.drawable.ic_steering
        "pdc" -> R.drawable.ic_pdc
        "dome" -> R.drawable.ic_dome
        "seat" -> R.drawable.ic_seat
        "tailgate" -> R.drawable.ic_tailgate
        else -> R.drawable.ic_module
    }
}
