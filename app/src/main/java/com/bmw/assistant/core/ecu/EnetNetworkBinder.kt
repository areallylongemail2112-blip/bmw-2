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
        val chosen = manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }?.takeIf { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@takeIf false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        if (chosen != null) {
            manager.bindProcessToNetwork(chosen)
            bound = chosen
        }
    }

    fun unbind() {
        if (bound == null) return
        runCatching { cm?.bindProcessToNetwork(null) }
        bound = null
    }
}
