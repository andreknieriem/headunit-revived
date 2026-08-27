package com.andrerinas.openheadunit.connection.wifi.direct

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Whether this head unit's WiFi radio has a 5 GHz band at all.
 *
 * Two reporter threads argued for weeks about a band nobody could establish. One unit is 2.4 GHz
 * only and said so in a comment; another has a working 5 GHz radio and was read as if it did not.
 * Neither fact reached a log, so no capture could be sorted into the right arm.
 * `WifiManager.is5GHzBandSupported()` has answered this since API 21 and this app has never asked.
 *
 * **Only a `false` is worth acting on.** [SoftApBandPolicy][com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBandPolicy]
 * already records why: the call describes the station side, and vendors ship radios that answer yes
 * there and still refuse to host anything on it. A no means the band is not present, which is a
 * hardware fact and is safe to build on; a yes means only that the station could join one.
 */
object WifiBandCapability {

    /**
     * True, false, or null when the platform could not be asked.
     *
     * Null rather than a default, because the two answers mean opposite things to a caller and a
     * guess would be indistinguishable from a reading. Below API 21 the method does not exist, and
     * the read can throw on a unit whose WiFi service is unavailable.
     */
    fun supports5Ghz(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            wifiManager.is5GHzBandSupported
        } catch (e: Exception) {
            AppLog.d("WifiBandCapability: could not read the 5 GHz band capability: ${e.message}")
            null
        }
    }

    /** How the answer reads in a log line a reporter pastes into an issue. */
    fun describe(supports5Ghz: Boolean?): String = when (supports5Ghz) {
        true -> "this unit's WiFi radio reports a 5 GHz band"
        false -> "this unit's WiFi radio has no 5 GHz band, so every wireless route here runs on 2.4 GHz"
        null -> "this unit will not say whether it has a 5 GHz band (below Android 5.0, or the " +
            "WiFi service would not answer), so nothing here is decided on the band"
    }
}
