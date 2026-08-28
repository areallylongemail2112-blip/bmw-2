package com.bmw.assistant.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bmw.assistant.R
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.core.ecu.ConnectionStatus
import com.bmw.assistant.feature.connection.ConnectionActivity
import com.google.android.material.chip.Chip

/**
 * Wires a toolbar [Chip] to the global [ConnectionManager] so every screen shows a live
 * connection indicator and can jump to the connection screen. Reused by all activities.
 */
object ConnectionBadge {
    fun bind(chip: Chip, owner: LifecycleOwner, context: Context) {
        chip.setOnClickListener {
            context.startActivity(Intent(context, ConnectionActivity::class.java))
        }
        ConnectionManager.state.observe(owner) { state ->
            val (text, colorRes) = when (state.status) {
                ConnectionStatus.CONNECTED ->
                    (state.label ?: "Connected") to R.color.status_connected
                ConnectionStatus.CONNECTING -> "Connecting…" to R.color.status_connecting
                ConnectionStatus.ERROR -> "Error" to R.color.status_error
                ConnectionStatus.DISCONNECTED -> "Not connected" to R.color.status_disconnected
            }
            chip.text = text
            chip.chipBackgroundColor =
                ContextCompat.getColorStateList(context, colorRes)
        }
    }
}
