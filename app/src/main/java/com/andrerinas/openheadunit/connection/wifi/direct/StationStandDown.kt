package com.andrerinas.openheadunit.connection.wifi.direct

import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions

/**
 * Drops and restores this unit's own WiFi association around a Native AA WiFi Direct bring-up.
 *
 * The rules are [StationStandDownPolicy]'s; this only does the I/O and reports what the platform
 * actually did. Nothing here trusts a return value: `disconnect()` sits behind a void AIDL on API 27
 * and answers true whatever happened, so the supplicant state is read back instead.
 */
object StationStandDown {

    /**
     * How long to give the supplicant before reading back whether it left.
     *
     * This runs on the service's main thread on the way to creating the group, so it cannot sleep.
     * The caller delays the group by this much instead when [standDown] says it acted: a group
     * asked for while the station is still tearing down forms on the channel the stand-down was
     * meant to free, and a credential refresh reads a group again rather than remaking it, so
     * nothing later would move it.
     */
    const val VERIFY_DELAY_MS = 1_500L

    /**
     * Leave the current network, recording it first so it can always be put back.
     *
     * The record is written before the call, not after: a crash between the two would otherwise
     * leave the unit unable to rejoin the owner's home network with nothing anywhere saying why.
     *
     * @return true when the unit was asked to leave, so the caller can give it [VERIFY_DELAY_MS].
     */
    fun standDown(context: Context): Boolean {
        val settings = try {
            App.provide(context).settings
        } catch (e: Exception) {
            AppLog.d("StationStandDown: settings unavailable, not standing down: ${e.message}")
            return false
        }
        if (!settings.standDownStationForWifiDirect) return false

        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            // supplicantState rather than the SSID or the network id alone, for the reason
            // logStationCoexistence gives: both of those are redacted whenever the location gate is
            // unsatisfied, which on a head unit is routine.
            val associated = info?.supplicantState == SupplicantState.COMPLETED
            val networkId = info?.networkId ?: -1
            val overlay = AppPermissions.isOverlayGranted(context)

            if (!StationStandDownPolicy.shouldStandDown(
                    enabled = settings.standDownStationForWifiDirect,
                    sdkInt = Build.VERSION.SDK_INT,
                    canDrawOverlays = overlay,
                    associated = associated,
                    networkId = networkId
                )
            ) {
                val why = StationStandDownPolicy.describeUnavailable(Build.VERSION.SDK_INT, overlay)
                when {
                    why != null -> AppLog.w("StationStandDown: $why")
                    !associated -> AppLog.i(
                        "StationStandDown: this unit is not joined to another WiFi network, so " +
                            "there is nothing to stand down before creating the group."
                    )
                    else -> AppLog.w(
                        "StationStandDown: this unit is joined to a network the app is not allowed " +
                            "to name, so it will not be disabled. Turning Location on usually makes " +
                            "it readable."
                    )
                }
                return false
            }

            settings.stationStandDownNetworkId = networkId
            @Suppress("DEPRECATION")
            val disabled = wm.disableNetwork(networkId)
            @Suppress("DEPRECATION")
            wm.disconnect()
            AppLog.i(
                "StationStandDown: asked this unit to leave its WiFi network so the group can have " +
                    "the radio to itself (disableNetwork returned $disabled). It is rejoined when " +
                    "the session ends."
            )

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val still = wm.connectionInfo?.supplicantState == SupplicantState.COMPLETED
                    if (still) {
                        AppLog.w(
                            "StationStandDown: this unit is still joined to its WiFi network " +
                                "${VERIFY_DELAY_MS}ms later, so the group will have to share that " +
                                "network's channel."
                        )
                    } else {
                        AppLog.i("StationStandDown: this unit has left its WiFi network.")
                    }
                } catch (e: Exception) {
                    AppLog.d("StationStandDown: could not read the station back: ${e.message}")
                }
            }, VERIFY_DELAY_MS)
            return true
        } catch (e: Exception) {
            AppLog.w("StationStandDown: could not stand the station down: ${e.message}")
            return false
        }
    }

    /**
     * Rejoin whatever [standDown] left disabled.
     *
     * Safe to call when nothing is standing, and deliberately called from more places than there are
     * stand-downs: a force-stop or a crash runs no teardown, so the next service start restores too.
     */
    fun restore(context: Context) {
        val settings = try {
            App.provide(context).settings
        } catch (e: Exception) {
            AppLog.d("StationStandDown: settings unavailable, cannot restore: ${e.message}")
            return
        }

        val networkId = try {
            settings.stationStandDownNetworkId
        } catch (e: Exception) {
            -1
        }
        if (!StationStandDownPolicy.shouldRestore(networkId)) return

        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val enabled = wm.enableNetwork(networkId, false)
            @Suppress("DEPRECATION")
            wm.reconnect()
            if (enabled) {
                AppLog.i(
                    "StationStandDown: this unit's WiFi network is enabled again and should rejoin " +
                        "in a few seconds."
                )
            } else {
                AppLog.w(
                    "StationStandDown: the platform refused to re-enable this unit's WiFi network. " +
                        "It may have to be reconnected by hand."
                )
            }
        } catch (e: Exception) {
            AppLog.w("StationStandDown: could not restore this unit's WiFi network: ${e.message}")
        } finally {
            // Cleared whatever happened. A record we cannot act on would make every later start try
            // again against a network id that no longer means anything.
            try {
                settings.stationStandDownNetworkId = -1
            } catch (e: Exception) {
                AppLog.d("StationStandDown: could not clear the record: ${e.message}")
            }
        }
    }
}
