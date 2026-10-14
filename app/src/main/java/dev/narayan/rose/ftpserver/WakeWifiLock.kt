package dev.narayan.rose.ftpserver

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

class WakeWifiLock(context: Context, tag: String) {
    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val wakeLock: PowerManager.WakeLock? = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)?.apply {
        setReferenceCounted(false)
    }

    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock? = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)?.apply {
        setReferenceCounted(false)
    }

    var isAcquired: Boolean = false
        set(value) {
            if (field == value) return
            try {
                if (value) {
                    wakeLock?.acquire(24 * 60 * 60 * 1000L /* 24 hours */)
                    wifiLock?.acquire()
                } else {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                    if (wifiLock?.isHeld == true) {
                        wifiLock.release()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            field = value
        }
}

