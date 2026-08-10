package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Reads the SSID and passphrase of the hotspot this device is configured to run.
 *
 * Reflection throughout — neither `getSoftApConfiguration` nor `getWifiApConfiguration` is public
 * API, and both can throw on a locked-down device — so callers want a manual override ahead of it.
 * Shared by `ShareHotspotQrDialog` and the Native AA hotspot transport.
 */
object HotspotConfigReader {

    /** The configured hotspot as (ssid, passphrase), or null if it cannot be read. */
    fun getSystemHotspotConfig(context: Context): Pair<String, String>? {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // 1. Try modern getSoftApConfiguration (API 30+)
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val getSoftApConfigurationMethod = wm.javaClass.getMethod("getSoftApConfiguration")
                    val softApConfig = getSoftApConfigurationMethod.invoke(wm)
                    if (softApConfig != null) {
                        val getSsidMethod = softApConfig.javaClass.getMethod("getSsid")
                        val getPassphraseMethod = softApConfig.javaClass.getMethod("getPassphrase")
                        val ssid = getSsidMethod.invoke(softApConfig) as? String ?: ""
                        val pass = getPassphraseMethod.invoke(softApConfig) as? String ?: ""
                        if (ssid.isNotEmpty()) {
                            return Pair(ssid, pass)
                        }
                    }
                } catch (e: Exception) {
                    AppLog.d("HotspotConfigReader: Failed to get soft ap config via reflection: ${e.message}")
                }
            }

            // 2. Try legacy getWifiApConfiguration (API < 30)
            try {
                val getWifiApConfigurationMethod = wm.javaClass.getMethod("getWifiApConfiguration")
                val wifiConfig = getWifiApConfigurationMethod.invoke(wm)
                if (wifiConfig != null) {
                    val ssidField = wifiConfig.javaClass.getField("SSID")
                    val preSharedKeyField = wifiConfig.javaClass.getField("preSharedKey")
                    val ssid = ssidField.get(wifiConfig) as? String ?: ""
                    val pass = preSharedKeyField.get(wifiConfig) as? String ?: ""

                    // Clean SSID quotes if present
                    val cleanSsid = if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid.substring(1, ssid.length - 1)
                    } else {
                        ssid
                    }
                    return Pair(cleanSsid, pass)
                }
            } catch (e: Exception) {
                AppLog.d("HotspotConfigReader: Failed to get wifi ap config via reflection: ${e.message}")
            }
        } catch (e: Exception) {
            AppLog.e("HotspotConfigReader: Failed to access WifiManager: ${e.message}")
        }
        return null
    }
}
