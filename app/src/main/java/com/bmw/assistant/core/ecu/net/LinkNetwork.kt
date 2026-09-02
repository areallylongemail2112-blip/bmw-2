package com.bmw.assistant.core.ecu.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Binds the app's ECU sockets to the physical link the car is on.
 *
 * An ENET cable (USB-C Ethernet adapter), an ENET-WiFi adapter and a WiFi OBD dongle all offer
 * a network with **no internet access**. Android therefore keeps cellular as the process default,
 * and a plain `Socket()` to 192.168.0.10 is routed out the mobile interface and times out — the
 * single most common "it won't connect" failure on modern Android.
 *
 * [acquire] asks ConnectivityManager for a Wi-Fi or Ethernet network *without* requiring
 * `NET_CAPABILITY_INTERNET` and remembers it; [bind] then pins each socket to it. Sockets are
 * bound individually rather than binding the whole process, so nothing else in the app is
 * affected and a failure here degrades to today's behaviour instead of breaking the link.
 */
object LinkNetwork {

    @Volatile private var network: Network? = null
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The local network currently pinned, or null when none was found. */
    val isAvailable: Boolean get() = network != null

    /**
     * Finds a local Wi-Fi/Ethernet network and waits up to [timeoutMs] for it. Safe to call
     * repeatedly; a previous registration is released first. Never throws — if it fails, the
     * transports fall back to the default network and behave as they did before.
     *
     * This *listens* for a matching network rather than asking the system to bring one up:
     * `registerNetworkCallback` needs only `ACCESS_NETWORK_STATE`, while `requestNetwork` also
     * requires `CHANGE_NETWORK_STATE`. The link is already connected by the time the user taps
     * connect — the phone is on the adapter's Wi-Fi, or the Ethernet dongle is plugged in — so
     * there is nothing to bring up, only something to find.
     */
    @Synchronized
    fun acquire(context: Context, timeoutMs: Long = ACQUIRE_TIMEOUT_MS) {
        release()
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val latch = CountDownLatch(1)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                network = available
                latch.countDown()
            }

            override fun onLost(lost: Network) {
                if (network == lost) network = null
            }
        }
        val ok = runCatching { cm.registerNetworkCallback(request, cb); true }.getOrDefault(false)
        if (!ok) return
        manager = cm
        callback = cb
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    /** Drops the registration so Android can go back to its normal routing. */
    @Synchronized
    fun release() {
        val cm = manager
        val cb = callback
        manager = null
        callback = null
        network = null
        if (cm != null && cb != null) runCatching { cm.unregisterNetworkCallback(cb) }
    }

    /** Pins [socket] to the local link, if one was acquired. */
    fun bind(socket: Socket) {
        val n = network ?: return
        runCatching { n.bindSocket(socket) }
    }

    /** Pins [socket] to the local link, if one was acquired. */
    fun bind(socket: DatagramSocket) {
        val n = network ?: return
        runCatching { n.bindSocket(socket) }
    }

    private const val ACQUIRE_TIMEOUT_MS = 4000L
}
