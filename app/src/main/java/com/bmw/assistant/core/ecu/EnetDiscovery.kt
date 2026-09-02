package com.bmw.assistant.core.ecu

import android.content.Context
import android.net.wifi.WifiManager
import com.bmw.assistant.core.ecu.net.LinkNetwork
import com.bmw.assistant.core.ecu.uds.Doip
import com.bmw.assistant.core.ecu.uds.Hsfz
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** One gateway found on the local network. */
data class EnetGateway(val ip: String, val protocol: EnetProtocol, val vin: String?) {
    val port: Int get() = if (protocol == EnetProtocol.HSFZ) Hsfz.PORT_TCP else Doip.PORT
    val label: String get() = "$ip  ${protocol.name}" + (vin?.let { "  VIN $it" } ?: "")
}

enum class EnetProtocol { HSFZ, DOIP }

/**
 * Finds the car's gateway on the network the phone is currently on, the same way E-Sys does:
 *  - HSFZ identification broadcast on UDP 6811 (F-series, incl. F10)
 *  - DoIP vehicle identification broadcast on UDP 13400 (G-series / late F-series)
 *
 * A direct ENET cable normally puts the ZGW at 192.168.0.10 (or 169.254.x.x when it falls back
 * to link-local); an ENET-WiFi adapter uses its own subnet. Broadcasting avoids guessing.
 *
 * The broadcast goes to **every** IPv4 broadcast address the phone's interfaces actually have,
 * not a hardcoded list, and the socket is pinned to the local link ([LinkNetwork]) because an
 * internet-less ENET network is never Android's default route.
 *
 * Anything that answers a broadcast is untrusted: an ENET-WiFi adapter and a WiFi OBD dongle
 * both run open access points, so a reported VIN is a hint for the user to confirm, never proof
 * of which car is on the other end.
 *
 * Blocking; call from a background thread.
 */
object EnetDiscovery {

    /** Gateways that answered, plus a human-readable note per probe that could not be sent. */
    data class Result(val gateways: List<EnetGateway>, val problems: List<String>) {
        val isEmpty: Boolean get() = gateways.isEmpty()
    }

    fun discover(context: Context? = null, timeoutMs: Int = DEFAULT_TIMEOUT_MS): List<EnetGateway> =
        discoverDetailed(context, timeoutMs).gateways

    fun discoverDetailed(context: Context? = null, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Result {
        val found = LinkedHashMap<Pair<String, EnetProtocol>, EnetGateway>()
        val problems = ArrayList<String>()
        val targets = broadcastAddresses()
        val multicastLock = acquireMulticastLock(context)
        try {
            runCatching {
                probe(Hsfz.PORT_UDP_IDENT, Hsfz.identificationRequest(), timeoutMs, targets) { ip, data ->
                    val frame = Hsfz.parse(data) ?: return@probe
                    if (frame.control == Hsfz.CTRL_VEHICLE_IDENT) {
                        found.putIfAbsent(
                            ip to EnetProtocol.HSFZ,
                            EnetGateway(ip, EnetProtocol.HSFZ, Hsfz.vinFromIdentification(frame.data))
                        )
                    }
                }
            }.onFailure { problems += "HSFZ discovery failed: ${it.message ?: it.javaClass.simpleName}" }

            runCatching {
                probe(Doip.PORT, Doip.vehicleIdentificationRequest(), timeoutMs, targets) { ip, data ->
                    val frame = Doip.parse(data) ?: return@probe
                    if (frame.payloadType == Doip.TYPE_VEHICLE_ANNOUNCEMENT) {
                        found.putIfAbsent(
                            ip to EnetProtocol.DOIP,
                            EnetGateway(ip, EnetProtocol.DOIP, Doip.vinFromAnnouncement(frame.payload))
                        )
                    }
                }
            }.onFailure { problems += "DoIP discovery failed: ${it.message ?: it.javaClass.simpleName}" }
        } finally {
            runCatching { multicastLock?.release() }
        }
        return Result(found.values.toList(), problems)
    }

    /**
     * Gateway replies are broadcast, and many Wi-Fi chipsets drop inbound broadcast frames
     * unless the app holds a multicast lock. Without it, DoIP discovery over an ENET-WiFi
     * adapter silently finds nothing.
     */
    private fun acquireMulticastLock(context: Context?): WifiManager.MulticastLock? {
        val wifi = context?.applicationContext
            ?.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching {
            wifi.createMulticastLock("bmw-assistant-enet-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    /** Every IPv4 broadcast address the phone's up interfaces expose, plus the global one. */
    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = LinkedHashSet<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.interfaceAddresses) {
                    val broadcast = address.broadcast ?: continue
                    if (broadcast is Inet4Address) addresses.add(broadcast)
                }
            }
        }
        runCatching { addresses.add(InetAddress.getByName("255.255.255.255")) }
        // Link-local fallback: a ZGW with no DHCP lease answers on 169.254/16.
        runCatching { addresses.add(InetAddress.getByName("169.254.255.255")) }
        return addresses.toList()
    }

    private fun probe(
        port: Int,
        request: ByteArray,
        timeoutMs: Int,
        targets: List<InetAddress>,
        onReply: (String, ByteArray) -> Unit
    ) {
        DatagramSocket().use { socket ->
            LinkNetwork.bind(socket)
            socket.broadcast = true
            socket.soTimeout = POLL_MS
            for (target in targets) {
                runCatching { socket.send(DatagramPacket(request, request.size, target, port)) }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(RECEIVE_BUFFER)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val ip = packet.address?.hostAddress ?: continue
                onReply(ip, packet.data.copyOf(packet.length))
            }
        }
    }

    private const val DEFAULT_TIMEOUT_MS = 2500
    private const val POLL_MS = 400
    private const val RECEIVE_BUFFER = 2048
}
