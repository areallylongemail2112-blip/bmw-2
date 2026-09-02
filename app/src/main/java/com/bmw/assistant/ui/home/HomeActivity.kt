package com.bmw.assistant.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bmw.assistant.databinding.ActivityHomeBinding
import com.bmw.assistant.feature.coding.CodingModulesActivity
import com.bmw.assistant.feature.connection.ConnectionActivity
import com.bmw.assistant.feature.diagnostics.DiagnosticsModulesActivity
import com.bmw.assistant.ui.common.ConnectionBadge
import com.bmw.assistant.ui.common.SafetyDisclaimer

/**
 * The launcher screen and hub. Two paths: **Coding** (change what the car does) and
 * **Diagnostics** (see what the car is doing). The toolbar connection chip is live on every
 * screen, and the one-time safety disclaimer is shown here before anything else.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Nothing in the app may be used before the safety disclaimer is acknowledged.
        SafetyDisclaimer.ensureAccepted(this)

        ConnectionBadge.bind(binding.connectionChip, this, this)

        binding.codingCard.setOnClickListener {
            startActivity(Intent(this, CodingModulesActivity::class.java))
        }
        binding.diagnosticsCard.setOnClickListener {
            startActivity(Intent(this, DiagnosticsModulesActivity::class.java))
        }
        binding.connectCard.setOnClickListener {
            startActivity(Intent(this, ConnectionActivity::class.java))
        }
    }
}
