package com.camradar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.camradar.core.discovery.CurrentNetwork
import java.net.Inet4Address

/** Reads the currently active network. Never touches any network other than the one the phone is on. */
class AndroidNetworkInfo(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    fun current(): CurrentNetwork? {
        val n = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(n) ?: return null
        val lp = cm.getLinkProperties(n) ?: return null
        val addr = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> CurrentNetwork.Transport.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> CurrentNetwork.Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> CurrentNetwork.Transport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CurrentNetwork.Transport.CELLULAR
            else -> CurrentNetwork.Transport.OTHER
        }
        @Suppress("DEPRECATION")
        val info = if (transport == CurrentNetwork.Transport.WIFI) wifi?.connectionInfo else null
        val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val bssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" }
        return CurrentNetwork(transport, ssid, bssid, addr.address.hostAddress!!, addr.prefixLength)
    }

    /** Multicast lock is needed to receive ONVIF/SSDP replies; held only for the duration of a scan. */
    suspend fun <T> withMulticastLock(block: suspend () -> T): T {
        val lock = context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("camradar-discovery")
        lock?.setReferenceCounted(false); lock?.acquire()
        try { return block() } finally { lock?.release() }
    }
}
