package com.andrerinas.openheadunit.connection.self

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.DummyVpnPolicy
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherBTDiscovery
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherBroadcast
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherLegacy
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherV17_4
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.VpnControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SelfLauncherManager(
    private val service: AapService,
    private val wifiLauncherManager: WifiLauncherManager
) {

    var isActive: Boolean = false

    /**
     * Takes down a Self Mode VPN whose phone never arrived.
     *
     * [stopWirelessServer] used to do this by accident. Without it a user who starts Self Mode and
     * walks away leaves a tun that routes 0.0.0.0/0 into a descriptor nobody reads, and the unit
     * has no IPv4 until the service dies.
     */
    private var selfModeVpnWatchdog: Job? = null

    /**
     * "Self Mode" connects the device to itself over the loopback interface.
     *
     * Starts [com.andrerinas.openheadunit.connection.wifi.server.WirelessServer] on port 5288, then launches the Google AA Wireless Setup
     * Activity pointing at `127.0.0.1:5288`. This causes the AA Wireless app to treat
     * the device as both the head unit and the phone, enabling a loopback session.
     *
     * [createFakeNetwork] and [createFakeWifiInfo] produce the Parcelable extras the
     * AA Wireless activity requires; they are constructed reflectively because the
     * relevant Android classes have no public constructors.
     */
    private fun isAaVersion174OrHigher(): Boolean {
        return try {
            val pInfo = service.packageManager.getPackageInfo(AA_PACKAGE, 0)
            val vName = pInfo.versionName ?: ""
            val parts = vName.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            AppLog.i("SelfMode: Installed AA version: $vName (major=$major, minor=$minor)")
            major > 17 || (major == 17 && minor >= 4)
        } catch (e: Exception) {
            AppLog.w("SelfMode: Failed to query AA version: ${e.message}")
            false
        }
    }

    fun openAaSettings() {
        val intent = Intent().apply {
            setClassName(
                AA_PACKAGE,
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            service.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent("android.settings.APPLICATION_DETAILS_SETTINGS").apply {
                    data = Uri.parse("package:com.google.android.projection.gearhead")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                AppLog.e("SelfMode: Failed to open AA settings: ${e2.message}")
            }
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun start() {
        val commManager = App.provide(service).commManager

        isActive = true
        adoptDummyVpn()

        service.serviceScope.launch(Dispatchers.Main) {
            // prepare launchers
            val services = SelfLauncherServices(service, wifiLauncherManager)
            val launchers: Array<SelfLauncher>

            if (isAaVersion174OrHigher()) {
                AppLog.i("SelfMode: AA 17.4+ detected. Connecting directly to Headunit Server on 127.0.0.1:5277...")
                launchers = arrayOf(
                    SelfLauncherV17_4(this@SelfLauncherManager, services)
                )

            } else {
                AppLog.i("SelfMode: AA < 17.4 detected. Starting WirelessServer on 5288 and running legacy triggers...")
                launchers = arrayOf(
                    SelfLauncherLegacy(this@SelfLauncherManager, services),
                    SelfLauncherBroadcast(this@SelfLauncherManager, services), // fallback #1
                    SelfLauncherBTDiscovery(this@SelfLauncherManager, services), // fallback #2
                )
            }

            // run them
            var anySucceeded = false

            for (launcher in launchers) {
                try {
                    if (!launcher.run())
                        AppLog.w("SelfMode: Launch of '${launcher.name}' failed")
                    else {
                        AppLog.w("SelfMode: Launch of '${launcher.name}' had no issues")
                        anySucceeded = true
                        break
                    }
                } catch (e: Exception) {
                    AppLog.w("SelfMode: Launch of '${launcher.name}' had caused an error", e)
                }
            }

            // all failed :(
            if (!anySucceeded) {
                AppLog.e("SelfMode: All launchers failed")
                commManager.emitError("No launch method succeeded") // hide "connecting" overlay
                return@launch
            }

            // run a timer to make sure it actually succeeded
            service.serviceScope.launch {
                delay(2500L)

                if (!commManager.isConnected && isActive) {
                    AppLog.e("SelfMode: All launchers failed (timeout)")
                    commManager.emitError("No launch method succeeded (timeout") // hide "connecting" overlay

                    handleNeverConnect()
                }
            }
        }
    }

    /**
     * Records that a Self Mode VPN - started by `HomeFragment`, which owns the consent dialog - is
     * ours to clean up, and arms the watchdog that does it if no phone ever arrives.
     */
    private fun adoptDummyVpn() {
        val commManager = App.provide(service).commManager

        // Nothing to adopt where the flavor has no VPN - see VpnControl.
        if (!VpnControl.isVpnAvailable()) return
        if (service.dummyVpnOwner == null) service.dummyVpnOwner = DummyVpnPolicy.Owner.SELF_MODE
        selfModeVpnWatchdog?.cancel()
        selfModeVpnWatchdog = service.serviceScope.launch {
            delay(VPN_TIMEOUT_MS)
            if (!commManager.isConnected) {
                AppLog.w(
                    "SelfMode",
                    "AapService: Self Mode brought the dummy VPN up ${VPN_TIMEOUT_MS}ms " +
                        "ago and no phone arrived. Taking it down so this unit gets its network back."
                )
                service.stopDummyVpn(DummyVpnPolicy.Reason.SELF_MODE_NEVER_CONNECTED)
            }
        }
    }

    fun stopDummyVpnWatchdog() {
        selfModeVpnWatchdog?.cancel()
        selfModeVpnWatchdog = null
    }

    fun handleNeverConnect() {
        AppLog.w("SelfMode: Failed, timed out!")

        SelfLaunchResolveHelper(service).run()
    }


    companion object {
        /**
         * How long a Self Mode dummy VPN may stay up with no phone before it is taken down.
         *
         * stopWirelessServer() used to do this cleanup by accident, on the next mode change.
         */
        private const val VPN_TIMEOUT_MS = 120_000L

        const val AA_PACKAGE = "com.google.android.projection.gearhead"
    }
}
