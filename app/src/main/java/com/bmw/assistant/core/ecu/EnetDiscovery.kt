package com.bmw.assistant.core.ecu

import com.bmw.assistant.core.ecu.uds.Doip
import com.bmw.assistant.core.ecu.uds.Hsfz
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
 * Blocking; call from a background thread.
 */
object EnetDiscovery {

    fun discover(timeoutMs: Int = 2500): List<EnetGateway> {
        val found = LinkedHashMap<String, EnetGateway>()
        runCatching { probe(Hsfz.PORT_UDP_IDENT, Hsfz.identificationRequest(), timeoutMs) { ip, data ->
            val frame = Hsfz.parse(data)
            if (frame != null && frame.control == Hsfz.CTRL_VEHICLE_IDENT) {
                found.putIfAbsent(ip, EnetGateway(ip, EnetProtocol.HSFZ, Hsfz.vinFromIdentification(frame.data)))
            }
        } }
        runCatching { probe(Doip.PORT, Doip.vehicleIdentificationRequest(), timeoutMs) { ip, data ->
            val frame = Doip.parse(data)
            if (frame != null && frame.payloadType == Doip.TYPE_VEHICLE_ANNOUNCEMENT) {
                found.putIfAbsent("$ip/doip", EnetGateway(ip, EnetProtocol.DOIP, Doip.vinFromAnnouncement(frame.payload)))
            }
        } }
        return found.values.toList()
    }

    private fun probe(port: Int, request: ByteArray, timeoutMs: Int, onReply: (String, ByteArray) -> Unit) {
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 400
            val targets = listOf("255.255.255.255", "192.168.0.255", "169.254.255.255")
            for (t in targets) {
                runCatching { sock.send(DatagramPacket(request, request.size, InetAddress.getByName(t), port)) }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                onReply(pkt.address.hostAddress ?: continue, pkt.data.copyOf(pkt.length))
            }
        }
    }
}
