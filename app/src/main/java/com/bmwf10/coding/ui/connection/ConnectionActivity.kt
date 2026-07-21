package com.bmwf10.coding.ui.connection

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
import com.bmwf10.coding.databinding.ActivityConnectionBinding
import com.bmwf10.coding.ecu.ConnectionStatus
import com.bmwf10.coding.ecu.ble.BleDevice
import com.bmwf10.coding.ecu.ble.BleObdTransport
import com.bmwf10.coding.ecu.ble.BleScanner
import com.google.android.material.snackbar.Snackbar

/** Screen 4 — choose how to connect: ENET/WiFi, BLE scan, or demo mode. */
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
        else snack("Bluetooth permission is required to scan for OBD adapters.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        bleAdapter = BleDeviceAdapter { device -> connectBle(device) }
        binding.bleList.layoutManager = LinearLayoutManager(this)
        binding.bleList.adapter = bleAdapter

        binding.demoButton.setOnClickListener { viewModel.connectDemo() }
        binding.enetButton.setOnClickListener {
            val ip = binding.ipInput.text.toString().trim()
            val port = binding.portInput.text.toString().trim().toIntOrNull() ?: 13400
            if (ip.isEmpty()) { snack("Enter the car/gateway IP address."); return@setOnClickListener }
            viewModel.connectEnet(ip, port)
        }
        binding.scanButton.setOnClickListener { ensurePermissionsThenScan() }
        binding.disconnectButton.setOnClickListener { viewModel.disconnect() }

        viewModel.state.observe(this) { s ->
            binding.statusText.text = when (s.status) {
                ConnectionStatus.CONNECTED -> "Connected — ${s.label}"
                ConnectionStatus.CONNECTING -> "Connecting to ${s.label}…"
                ConnectionStatus.ERROR -> "Error: ${s.message}"
                ConnectionStatus.DISCONNECTED -> "Not connected"
            }
            binding.progress.visibility =
                if (s.status == ConnectionStatus.CONNECTING) View.VISIBLE else View.GONE
            binding.disconnectButton.visibility =
                if (s.isConnected) View.VISIBLE else View.GONE
            if (s.status == ConnectionStatus.ERROR && s.message != null) snack(s.message)
        }
    }

    // --- BLE ---

    private fun requiredBlePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermissionsThenScan() {
        val missing = requiredBlePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startScan() {
        if (!scanner.isAvailable) { snack("Enable Bluetooth to scan for adapters."); return }
        found.clear(); bleAdapter.submitList(emptyList())
        binding.scanButton.text = "Scanning…"
        scanner.start { device ->
            runOnUiThread {
                if (found.none { it.address == device.address }) {
                    found.add(device)
                    bleAdapter.submitList(found.toList())
                }
            }
        }
        // Auto-stop after a reasonable scan window.
        binding.bleList.postDelayed({ stopScan() }, 8000)
    }

    private fun stopScan() {
        runCatching { scanner.stop() }
        binding.scanButton.text = "Scan for BLE adapters"
    }

    private fun connectBle(device: BleDevice) {
        stopScan()
        val remote = scanner.deviceFor(device.address)
        if (remote == null) { snack("Device no longer available."); return }
        viewModel.connectBle(device.name, BleObdTransport(this, remote))
    }

    override fun onPause() {
        super.onPause()
        stopScan()
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
