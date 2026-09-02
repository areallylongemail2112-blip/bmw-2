package com.bmw.assistant.feature.connection

import android.Manifest
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
import com.bmw.assistant.R
import com.bmw.assistant.core.ecu.ConnectionManager
import com.bmw.assistant.core.ecu.ConnectionState
import com.bmw.assistant.core.ecu.ConnectionStatus
import com.bmw.assistant.core.ecu.EnetGateway
import com.bmw.assistant.core.ecu.EnetProtocol
import com.bmw.assistant.core.ecu.ble.BleDevice
import com.bmw.assistant.core.ecu.ble.BleScanner
import com.bmw.assistant.databinding.ActivityConnectionBinding
import com.google.android.material.snackbar.Snackbar

/**
 * Choose how to connect: demo mode, ENET (HSFZ or DoIP, with network discovery), a paired
 * Bluetooth Classic OBD adapter, a BLE OBD adapter found by scanning, or a WiFi OBD adapter.
 */
class ConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionBinding
    private val viewModel: ConnectionViewModel by viewModels()

    private val scanner by lazy { BleScanner(this) }
    private lateinit var bleAdapter: BleDeviceAdapter
    private val found = mutableListOf<BleDevice>()
    private var scanning = false

    /** What to run once a permission request comes back granted. */
    private var afterPermission: (() -> Unit)? = null

    private enum class PendingAction { NONE, SCAN_BLE, PAIRED_BT, CONNECT_BLE }
    private var pending = PendingAction.NONE
    private var pendingBle: BleDevice? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = afterPermission
        afterPermission = null
        if (grants.values.all { it }) action?.invoke()
        else snack(getString(R.string.conn_bt_permission_needed))
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
        binding.discoverButton.setOnClickListener { viewModel.discoverGateways() }
        binding.enetButton.setOnClickListener { connectEnet() }
        binding.btPairedButton.setOnClickListener {
            withPermissions(bluetoothConnectPermissions()) { choosePairedAdapter() }
        }
        binding.scanButton.setOnClickListener {
            if (scanning) stopScan() else withPermissions(bleScanPermissions()) { startScan() }
        }
        binding.wifiObdButton.setOnClickListener { connectWifiObd() }
        binding.disconnectButton.setOnClickListener { viewModel.disconnect() }

        viewModel.state.observe(this) { s -> render(s) }
        viewModel.discovering.observe(this) { busy ->
            binding.discoverButton.isEnabled = !busy
            binding.discoverButton.text =
                getString(if (busy) R.string.conn_discovering else R.string.conn_discover)
        }
        viewModel.discovered.observe(this) { ev ->
            ev.getIfNotHandled()?.let { onGatewaysDiscovered(it) }
        }
        viewModel.notice.observe(this) { ev ->
            ev.getIfNotHandled()?.let { snack(it) }
        }
    }

    // --- rendering ---

    private fun render(state: ConnectionState) {
        binding.statusText.text = when (state.status) {
            ConnectionStatus.CONNECTED -> getString(R.string.conn_status_connected, state.label ?: "")
            ConnectionStatus.CONNECTING -> getString(R.string.conn_status_connecting, state.label ?: "")
            ConnectionStatus.ERROR -> getString(R.string.conn_status_error, state.message ?: "")
            ConnectionStatus.DISCONNECTED -> getString(R.string.conn_status_disconnected)
        }
        val connecting = state.status == ConnectionStatus.CONNECTING
        val connected = state.status == ConnectionStatus.CONNECTED
        binding.progress.visibility = if (connecting) View.VISIBLE else View.GONE
        binding.disconnectButton.visibility = if (connected) View.VISIBLE else View.GONE

        // Which car, over which link. Composed by ConnectionManager so every screen agrees.
        val vehicle = if (connected) state.vehicleInfo else null
        binding.vehicleText.text = vehicle.orEmpty()
        binding.vehicleText.visibility = if (vehicle.isNullOrBlank()) View.GONE else View.VISIBLE

        // One link at a time: no new connect attempts while one is in flight.
        listOf(
            binding.demoButton, binding.enetButton, binding.btPairedButton,
            binding.scanButton, binding.wifiObdButton
        ).forEach { it.isEnabled = !connecting }

        if (state.status == ConnectionStatus.ERROR && state.message != null) snack(state.message)
    }

    // --- ENET ---

    private fun connectEnet() {
        val ip = binding.ipInput.text?.toString()?.trim().orEmpty()
        if (ip.isEmpty()) { snack(getString(R.string.conn_enter_gateway_ip)); return }
        val protocol = if (binding.protocolGroup.checkedButtonId == R.id.protoDoip)
            EnetProtocol.DOIP else EnetProtocol.HSFZ
        viewModel.connectEnet(ip, protocol)
    }

    private fun onGatewaysDiscovered(gateways: List<EnetGateway>) {
        when (gateways.size) {
            0 -> snack(getString(R.string.conn_no_gateway_found))
            1 -> selectGateway(gateways[0])
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.conn_choose_gateway)
                .setItems(gateways.map { it.label }.toTypedArray()) { _, i -> selectGateway(gateways[i]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun selectGateway(gw: EnetGateway) {
        binding.ipInput.setText(gw.ip)
        binding.protocolGroup.check(if (gw.protocol == EnetProtocol.DOIP) R.id.protoDoip else R.id.protoHsfz)
        snack(getString(R.string.conn_gateway_found, gw.label))
    }

    // --- Bluetooth Classic (paired SPP adapters) ---

    private fun choosePairedAdapter() {
        val adapter = bluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) { snack(getString(R.string.conn_enable_bluetooth)); return }
        val bonded = try {
            adapter.bondedDevices.orEmpty().toList()
        } catch (_: SecurityException) {
            snack(getString(R.string.conn_bt_permission_needed)); return
        }
        if (bonded.isEmpty()) { snack(getString(R.string.conn_no_paired_adapters)); return }
        val names = bonded.map { d -> deviceLabel(d) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.conn_choose_paired)
            .setItems(names) { _, i -> viewModel.connectObdBluetooth(bonded[i], names[i]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun startScan() {
        if (!scanner.isAvailable) { snack(getString(R.string.conn_enable_bluetooth)); return }
        found.clear(); bleAdapter.submitList(emptyList())
        scanning = true
        binding.scanButton.text = getString(R.string.conn_scanning)
        scanner.start { device ->
            runOnUiThread {
                if (found.none { it.address == device.address }) {
                    found.add(device)
                    bleAdapter.submitList(found.toList())
                }
            }
        }
        // Auto-stop after a reasonable scan window.
        binding.bleList.postDelayed({ stopScan() }, SCAN_WINDOW_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { scanner.stop() }
        binding.scanButton.text = getString(R.string.scan_ble)
        if (found.isEmpty()) snack(getString(R.string.conn_no_ble_found))
    }

    private fun connectBle(device: BleDevice) {
        // Connecting needs BLUETOOTH_CONNECT on API 31+, which scanning alone does not grant.
        withPermissions(bleScanPermissions()) {
            stopScan()
            val remote = scanner.deviceFor(device.address)
            if (remote == null) { snack(getString(R.string.conn_device_gone)); return@withPermissions }
            viewModel.connectObdBle(remote, device.name)
        }
    }

    // --- WiFi OBD ---

    private fun connectWifiObd() {
        val ip = binding.wifiIpInput.text?.toString()?.trim().orEmpty()
        val port = binding.wifiPortInput.text?.toString()?.trim()?.toIntOrNull()
        if (ip.isEmpty()) { snack(getString(R.string.conn_enter_adapter_ip)); return }
        if (port == null || port !in 1..65535) { snack(getString(R.string.conn_enter_valid_port)); return }
        viewModel.connectObdWifi(ip, port)
    }

    // --- permissions ---

    private fun bluetoothConnectPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray()

    private fun bleScanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun withPermissions(permissions: Array<String>, action: () -> Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun deviceLabel(d: BluetoothDevice): String {
        val name = try { d.name } catch (_: SecurityException) { null }
        return if (name.isNullOrBlank()) d.address else "$name (${d.address})"
    }

    override fun onPause() {
        super.onPause()
        stopScan()
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()

    private companion object {
        const val SCAN_WINDOW_MS = 8000L
    }
}
