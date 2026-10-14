package dev.narayan.rose.ftpserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import dev.narayan.rose.SettingsManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object FtpServerUrl {

    fun getLocalIpAddress(context: Context): InetAddress? {
        // 1. Try WifiManager
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipAddress = wifiInfo?.ipAddress ?: 0
            if (ipAddress != 0) {
                val bytes = byteArrayOf(
                    (ipAddress and 0xff).toByte(),
                    (ipAddress shr 8 and 0xff).toByte(),
                    (ipAddress shr 16 and 0xff).toByte(),
                    (ipAddress shr 24 and 0xff).toByte()
                )
                return InetAddress.getByAddress(bytes)
            }
        } catch (e: Exception) {
            // fallback
        }

        // 2. Try ConnectivityManager LinkProperties
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            if (activeNetwork != null) {
                val linkProperties = cm.getLinkProperties(activeNetwork)
                linkProperties?.linkAddresses?.forEach { linkAddress ->
                    val addr = linkAddress.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }

        // 3. Fallback: Iterate NetworkInterfaces (handles hotspot / tethering / wlan)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && (addr.isSiteLocalAddress || !addr.isLinkLocalAddress)) {
                        return addr
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    fun getUrl(context: Context, settings: SettingsManager): String? {
        val localAddress = getLocalIpAddress(context) ?: return null
        val host = localAddress.hostAddress ?: return null
        val port = settings.ftpServerPort
        val username = if (!settings.ftpServerAnonymousLogin && settings.ftpServerUsername.isNotBlank()) {
            "${settings.ftpServerUsername}@"
        } else {
            ""
        }
        return "ftp://$username$host:$port/"
    }
}

