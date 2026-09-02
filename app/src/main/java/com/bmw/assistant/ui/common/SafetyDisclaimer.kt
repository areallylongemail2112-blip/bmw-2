package com.bmw.assistant.ui.common

import android.app.Activity
import android.content.Context
import com.bmw.assistant.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The one-time acknowledgement shown before the app can be used.
 *
 * Coding writes configuration bytes to a car's control modules, and the bundled maps are
 * illustrative rather than verified against any particular car. A README paragraph is not an
 * acceptable place for that warning in a shipped product, so acceptance is recorded here and the
 * app is unusable until the driver has read it.
 *
 * The flag is stored per install. It is deliberately *not* included in cloud backup (see
 * `data_extraction_rules.xml`), so a restored install asks again.
 */
object SafetyDisclaimer {

    fun hasAccepted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACCEPTED, false)

    /**
     * Shows the disclaimer if it has not been accepted yet. Declining finishes [activity] —
     * there is no partial mode where the car can be touched without acknowledgement.
     */
    fun ensureAccepted(activity: Activity, onAccepted: () -> Unit = {}) {
        if (hasAccepted(activity)) {
            onAccepted()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_body)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                prefs(activity).edit().putBoolean(KEY_ACCEPTED, true).apply()
                onAccepted()
            }
            .setNegativeButton(R.string.disclaimer_decline) { _, _ -> activity.finish() }
            .show()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "safety"
    private const val KEY_ACCEPTED = "disclaimer_accepted_v1"
}
