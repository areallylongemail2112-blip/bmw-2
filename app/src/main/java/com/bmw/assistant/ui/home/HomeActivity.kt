package com.bmw.assistant.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bmw.assistant.databinding.ActivityHomeBinding
import com.bmw.assistant.feature.backups.BackupsActivity
import com.bmw.assistant.feature.coding.CodingModulesActivity
import com.bmw.assistant.feature.connection.ConnectionActivity
import com.bmw.assistant.feature.diagnostics.DiagnosticsModulesActivity
import com.bmw.assistant.feature.service.ServicesActivity
import com.bmw.assistant.ui.common.ConnectionBadge

/**
 * The launcher screen and hub. Paths: Coding, Diagnostics, Service functions,
 * Backups, and Connection. The toolbar connection chip is live on every screen.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConnectionBadge.bind(binding.connectionChip, this, this)

        binding.codingCard.setOnClickListener {
            startActivity(Intent(this, CodingModulesActivity::class.java))
        }
        binding.diagnosticsCard.setOnClickListener {
            startActivity(Intent(this, DiagnosticsModulesActivity::class.java))
        }
        binding.servicesCard.setOnClickListener {
            startActivity(Intent(this, ServicesActivity::class.java))
        }
        binding.backupsCard.setOnClickListener {
            startActivity(Intent(this, BackupsActivity::class.java))
        }
        binding.connectCard.setOnClickListener {
            startActivity(Intent(this, ConnectionActivity::class.java))
        }
    }
}
