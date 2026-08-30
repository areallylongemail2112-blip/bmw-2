package com.bmw.assistant.core.ecu

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Pins the process to a Wi-Fi or Ethernet network so ENET/DoIP packets do not
 * hop onto cellular mid-session. Unbind on disconnect.
 */
class EnetNetworkBinder(context: Context) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    @Volatile private var bound: Network? = null

    fun bindPreferringLocalLan() {
        val manager = cm ?: return
        val network = manager.activeNetwork ?: return
        val caps = manager.getNetworkCapabilities(network) ?: return
        val localLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!localLan) return
        manager.bindProcessToNetwork(network)
        bound = network
    }

    fun unbind() {
        if (bound == null) return
        runCatching { cm?.bindProcessToNetwork(null) }
        bound = null
    }
}
