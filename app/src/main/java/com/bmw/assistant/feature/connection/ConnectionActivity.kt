package com.bmw.assistant.feature.connection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bmw.assistant.R
import com.bmw.assistant.core.ecu.ConnectionStatus
import com.bmw.assistant.core.ecu.ble.BleDevice
import com.bmw.assistant.core.ecu.ble.BleObdTransport
import com.bmw.assistant.core.ecu.ble.BleScanner
import com.bmw.assistant.databinding.ActivityConnectionBinding
import com.bmw.assistant.ui.common.ConnectionBadge
import com.google.android.material.snackbar.Snackbar

/** Choose how to connect: ENET/WiFi, BLE handshake, or demo mode. */
class ConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionBinding
    private val viewModel: ConnectionViewModel by viewModels()

    private val scanner by lazy { BleScanner(this) }
    private lateinit var bleAdapter: BleDeviceAdapter
    private val found = mutableListOf<BleDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScan()
        else snack(getString(R.string.ble_permission_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        ConnectionBadge.bind(binding.connectionChip, this, this)

        bleAdapter = BleDeviceAdapter { device -> connectBle(device) }
        binding.bleList.layoutManager = LinearLayoutManager(this)
        binding.bleList.adapter = bleAdapter

        binding.demoButton.setOnClickListener { viewModel.connectDemo() }
        binding.enetButton.setOnClickListener {
            val ip = binding.ipInput.text.toString().trim()
            val port = binding.portInput.text.toString().trim().toIntOrNull() ?: 13400
            if (ip.isEmpty()) { snack(getString(R.string.enter_ip)); return@setOnClickListener }
            viewModel.connectEnet(ip, port)
        }
        binding.scanButton.setOnClickListener { ensurePermissionsThenScan() }
        binding.disconnectButton.setOnClickListener { viewModel.disconnect() }

        viewModel.state.observe(this) { s ->
            binding.statusText.text = when (s.status) {
                ConnectionStatus.CONNECTED -> getString(R.string.connected_as, s.label)
                ConnectionStatus.CONNECTING -> getString(R.string.connecting_to, s.label)
                ConnectionStatus.ERROR -> getString(R.string.connection_error, s.message)
                ConnectionStatus.DISCONNECTED -> getString(R.string.not_connected)
            }
            binding.progress.visibility =
                if (s.status == ConnectionStatus.CONNECTING) View.VISIBLE else View.GONE
            binding.disconnectButton.visibility =
                if (s.isConnected) View.VISIBLE else View.GONE
            val identity = s.identity
            if (identity?.vin != null) {
                binding.identityText.visibility = View.VISIBLE
                binding.identityText.text = if (identity.iLevel != null)
                    getString(R.string.vehicle_identity, identity.vin, identity.iLevel)
                else
                    getString(R.string.vehicle_identity_vin, identity.vin)
            } else {
                binding.identityText.visibility = View.GONE
            }
            if (s.status == ConnectionStatus.ERROR && s.message != null) snack(s.message)
        }
    }

    private fun requiredBlePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermissionsThenScan() {
        val missing = requiredBlePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startScan() {
        if (!scanner.isAvailable) { snack(getString(R.string.enable_bluetooth)); return }
        found.clear(); bleAdapter.submitList(emptyList())
        binding.scanButton.text = getString(R.string.scanning)
        scanner.start { device ->
            runOnUiThread {
                if (found.none { it.address == device.address }) {
                    found.add(device)
                    bleAdapter.submitList(found.toList())
                }
            }
        }
        binding.bleList.postDelayed({ stopScan(announce = true) }, 8000)
    }

    private fun stopScan(announce: Boolean = false) {
        runCatching { scanner.stop() }
        binding.scanButton.text = getString(R.string.scan_ble)
        if (announce) snack(getString(R.string.scan_finished, found.size))
    }

    private fun connectBle(device: BleDevice) {
        stopScan()
        val remote = scanner.deviceFor(device.address)
        if (remote == null) { snack(getString(R.string.device_gone)); return }
        viewModel.connectBle(device.name, BleObdTransport(this, remote))
    }

    override fun onPause() {
        super.onPause()
        stopScan()
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
