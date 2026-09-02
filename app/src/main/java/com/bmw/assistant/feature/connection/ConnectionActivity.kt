package com.bmw.assistant.feature.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bmw.assistant.core.ecu.ConnectionStatus
import com.bmw.assistant.core.ecu.EnetGateway
import com.bmw.assistant.core.ecu.EnetProtocol
import com.bmw.assistant.core.ecu.ble.BleDevice
import com.bmw.assistant.core.ecu.ble.BleScanner
import com.bmw.assistant.databinding.ActivityConnectionBinding
import com.google.android.material.snackbar.Snackbar

/** Choose how to connect: demo, ENET (HSFZ/DoIP), or an ELM327 OBD adapter (BT/BLE/WiFi). */
class ConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionBinding
    private val viewModel: ConnectionViewModel by viewModels()

    private val scanner by lazy { BleScanner(this) }
    private lateinit var bleAdapter: BleDeviceAdapter
    private val found = mutableListOf<BleDevice>()

    private enum class PendingAction { NONE, SCAN_BLE, PAIRED_BT, CONNECT_BLE }
    private var pending = PendingAction.NONE
    private var pendingBle: BleDevice? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) runPending()
        else snack("Bluetooth permission is required to talk to an OBD adapter.")
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
        binding.discoverButton.setOnClickListener { viewModel.discoverEnet() }
        binding.enetButton.setOnClickListener {
            val ip = binding.ipInput.text.toString()
            val hsfz = binding.protocolGroup.checkedButtonId != binding.protoDoip.id
            viewModel.connectEnet(ip, hsfz)
        }
        binding.btPairedButton.setOnClickListener {
            pending = PendingAction.PAIRED_BT
            ensureBtPermissionsThenRun()
        }
        binding.scanButton.setOnClickListener {
            pending = PendingAction.SCAN_BLE
            ensureBtPermissionsThenRun()
        }
        binding.wifiObdButton.setOnClickListener {
            val ip = binding.wifiIpInput.text.toString()
            val port = binding.wifiPortInput.text.toString().trim().toIntOrNull() ?: 35000
            viewModel.connectObdWifi(ip, port)
        }
        binding.disconnectButton.setOnClickListener { viewModel.disconnect() }

        viewModel.state.observe(this) { s ->
            binding.statusText.text = when (s.status) {
                ConnectionStatus.CONNECTED -> "Connected — ${s.label}"
                ConnectionStatus.CONNECTING -> "Connecting to ${s.label}…"
                ConnectionStatus.ERROR -> "Error: ${s.message}"
                ConnectionStatus.DISCONNECTED -> "Not connected"
            }
            val info = s.vehicleInfo
            if (!info.isNullOrBlank() && s.isConnected) {
                binding.vehicleText.visibility = View.VISIBLE
                binding.vehicleText.text = info
            } else {
                binding.vehicleText.visibility = View.GONE
            }
            binding.progress.visibility =
                if (s.status == ConnectionStatus.CONNECTING) View.VISIBLE else View.GONE
            binding.disconnectButton.visibility =
                if (s.isConnected) View.VISIBLE else View.GONE
            val busy = s.status == ConnectionStatus.CONNECTING
            binding.demoButton.isEnabled = !busy
            binding.enetButton.isEnabled = !busy
            binding.discoverButton.isEnabled = !busy
            binding.btPairedButton.isEnabled = !busy
            binding.scanButton.isEnabled = !busy
            binding.wifiObdButton.isEnabled = !busy
            if (s.status == ConnectionStatus.ERROR && s.message != null) snack(s.message)
        }

        viewModel.gateways.observe(this) { event ->
            val list = event.getIfNotHandled() ?: return@observe
            if (list.isEmpty()) return@observe
            applyGateway(list)
        }
        viewModel.notice.observe(this) { event ->
            event.getIfNotHandled()?.let { snack(it) }
        }
    }

    private fun applyGateway(list: List<EnetGateway>) {
        if (list.size == 1) {
            selectGateway(list[0])
            snack("Found ${list[0].label}")
            return
        }
        val labels = list.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Gateways on this network")
            .setItems(labels) { _, which -> selectGateway(list[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectGateway(gw: EnetGateway) {
        binding.ipInput.setText(gw.ip)
        binding.protocolGroup.check(
            if (gw.protocol == EnetProtocol.HSFZ) binding.protoHsfz.id else binding.protoDoip.id
        )
    }

    // --- Bluetooth ---

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensureBtPermissionsThenRun() {
        val missing = bluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) runPending() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun runPending() {
        when (pending) {
            PendingAction.SCAN_BLE -> startScan()
            PendingAction.PAIRED_BT -> showPairedChooser()
            PendingAction.CONNECT_BLE -> pendingBle?.let { connectBle(it) }
            PendingAction.NONE -> Unit
        }
        pending = PendingAction.NONE
        pendingBle = null
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    private fun showPairedChooser() {
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            snack("Enable Bluetooth, then pair the OBD adapter in Android settings.")
            return
        }
        val bonded: List<BluetoothDevice> = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            snack("Bluetooth permission is required to list paired adapters.")
            return
        }
        if (bonded.isEmpty()) {
            snack("No paired Bluetooth devices. Pair the OBD adapter in Android settings first.")
            return
        }
        val labels = bonded.map { (it.name ?: "Unknown") + "\n" + it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Paired Bluetooth adapters")
            .setItems(labels) { _, which ->
                val device = bonded[which]
                runCatching { adapter.cancelDiscovery() }
                viewModel.connectObdBluetooth(device, device.name ?: device.address)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        binding.bleList.postDelayed({ stopScan() }, 10_000)
    }

    private fun stopScan() {
        runCatching { scanner.stop() }
        binding.scanButton.text = getString(com.bmw.assistant.R.string.scan_ble)
    }

    private fun connectBle(device: BleDevice) {
        val missing = bluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pending = PendingAction.CONNECT_BLE
            pendingBle = device
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        stopScan()
        val remote = scanner.deviceFor(device.address)
        if (remote == null) { snack("Device no longer available."); return }
        viewModel.connectObdBle(this, remote, device.name)
    }

    override fun onPause() {
        super.onPause()
        stopScan()
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
