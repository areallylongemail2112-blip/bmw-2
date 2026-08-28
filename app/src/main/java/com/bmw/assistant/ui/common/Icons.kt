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
        else -> R.drawable.ic_module
    }
}
