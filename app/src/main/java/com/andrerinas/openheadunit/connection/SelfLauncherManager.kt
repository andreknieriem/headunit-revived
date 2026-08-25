package com.andrerinas.openheadunit.connection

import android.annotation.SuppressLint
import android.content.Context.CONNECTIVITY_SERVICE
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import android.widget.Toast
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.DummyVpnPolicy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServer
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.utils.VpnControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelfLauncherManager(
    private val service: AapService,
    private val wifiLauncherManager: WifiLauncherManager) {

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
     * Starts [WirelessServer] on port 5288, then launches the Google AA Wireless Setup
     * Activity pointing at `127.0.0.1:5288`. This causes the AA Wireless app to treat
     * the device as both the head unit and the phone, enabling a loopback session.
     *
     * [createFakeNetwork] and [createFakeWifiInfo] produce the Parcelable extras the
     * AA Wireless activity requires; they are constructed reflectively because the
     * relevant Android classes have no public constructors.
     */
    private fun isAaVersion174OrHigher(): Boolean {
        return try {
            val pInfo = service.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0)
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

    private fun openAaSettings() {
        val intent = Intent().apply {
            setClassName(
                "com.google.android.projection.gearhead",
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            service.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent("android.settings.APPLICATION_DETAILS_SETTINGS").apply {
                    data = android.net.Uri.parse("package:com.google.android.projection.gearhead")
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
            if (isAaVersion174OrHigher()) {
                AppLog.i("SelfMode: AA 17.4+ detected. Connecting directly to Headunit Server on 127.0.0.1:5277...")
                val success = withContext(Dispatchers.IO) {
                    commManager.connect("127.0.0.1", 5277)
                    commManager.isConnected
                }
                if (!success && !commManager.isConnected) {
                    AppLog.w("SelfMode: Headunit Server (127.0.0.1:5277) is NOT running.")
                    ToastUtils.showToast(
                        service,
                        "Android Auto 17.4+ detected: Please start 'Headunit Server' in Android Auto Developer Settings!",
                        Toast.LENGTH_LONG
                    )
                    openAaSettings()
                }
                return@launch
            }

            AppLog.i("SelfMode: AA < 17.4 detected. Starting WirelessServer on 5288 and running legacy triggers...")
            wifiLauncherManager.setActive(WifiLauncherMode.NATIVE)

            val connectivityManager = service.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager.activeNetwork == null) {
                // Wait up to 1 second for the Dummy VPN to become the active network
                for (i in 1..10) {
                    if (connectivityManager.activeNetwork != null) break
                    delay(100)
                }
            }

            val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                connectivityManager.activeNetwork else null
            val networkToUse = activeNetwork ?: createFakeNetwork(0)
            val fakeWifiInfo = createFakeWifiInfo()

            val magicalIntent = Intent().apply {
                setClassName(
                    "com.google.android.projection.gearhead",
                    "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                putExtra("PARAM_SERVICE_PORT", 5288)
                networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                fakeWifiInfo?.let { putExtra("wifi_info", it) }
            }

            try {
                AppLog.i("Launching AA Wireless Startup via Activity...")
                service.startActivity(magicalIntent)
            } catch (e: Exception) {
                AppLog.w("Activity launch failed (${e.message}). Attempting Broadcast fallback...")
                try {

                    AppLog.w("WirelessStartupActivity not found (AA 16.4+ detected).")
                    if (Build.VERSION.SDK_INT <= 29) {
                        // On Android 10, if Activity is gone, Broadcast will definitely be blocked by Gearhead's version check.
                        AppLog.e("Self-mode blocked by Google on Android 10 (AA 16.4+). Skipping broadcast fallback.")
                        ToastUtils.showToast(service, service.getString(R.string.failed_self_mode_android10), Toast.LENGTH_LONG)
                    } else {
                        val receiverIntent = Intent().apply {
                            setClassName(
                                "com.google.android.projection.gearhead",
                                "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
                            )
                            action = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
                            putExtra("ip_address", "127.0.0.1")
                            putExtra("projection_port", 5288)
                            networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                            fakeWifiInfo?.let { putExtra("wifi_info", it) }
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        service.sendBroadcast(receiverIntent)
                        AppLog.i("Broadcast fallback 1 (WirelessStartupReceiver) sent.")

                        // Fallback 2: WifiBluetoothReceiver (START_WIRELESS_PROJECTION) for AA 17.4+
                        val bondedAddress = try {
                            val adapter = BluetoothHelper.getBluetoothAdapter(service)
                            val bonded = adapter?.bondedDevices
                            val connectedDevice = bonded?.firstOrNull { dev ->
                                try {
                                    val m = dev.javaClass.getMethod("isConnected")
                                    (m.invoke(dev) as? Boolean) == true
                                } catch (e: Exception) { false }
                            }
                            val targetDev = connectedDevice ?: bonded?.firstOrNull()
                            val selfAddr: String? = try { adapter?.address } catch (se: SecurityException) { null }
                            AppLog.i("SelfMode BT Discovery: bondedCount=${bonded?.size ?: 0}, connectedMac=${connectedDevice?.address}, selectedMac=${targetDev?.address}")
                            targetDev?.address ?: if (!selfAddr.isNullOrBlank() && selfAddr != "02:00:00:00:00:00") selfAddr else null
                        } catch (e: Throwable) {
                            AppLog.w("Failed to get bonded BT device address: ${e.message}")
                            null
                        } ?: "00:11:22:33:44:55"

                        val btReceiverIntent = Intent("com.google.android.projection.gearhead.START_WIRELESS_PROJECTION").apply {
                            setClassName(
                                "com.google.android.projection.gearhead",
                                "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver"
                            )
                            putExtra("DEVICE_ADDRESS", bondedAddress)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        }
                        service.sendBroadcast(btReceiverIntent)
                        AppLog.i("Broadcast fallback 2 (WifiBluetoothReceiver START_WIRELESS_PROJECTION with MAC $bondedAddress) sent.")
                    }
                } catch (e2: Exception) {
                    AppLog.e("All triggers failed", e2)
                    ToastUtils.showToast(service, service.getString(R.string.failed_start_android_auto), Toast.LENGTH_SHORT)
                }
            }
        }
    }

    /** Reflectively constructs an `android.net.Network` from a raw network ID integer. */
    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeInt(netId)
            parcel.setDataPosition(0)
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as Parcelable
        } catch (e: Exception) { null } finally { parcel.recycle() }
    }

    /** Reflectively constructs a `WifiInfo` with a fake SSID for the Self Mode intent. */
    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo")
            val wifiInfo = wifiInfoClass.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance() as Parcelable
            try {
                wifiInfoClass.getDeclaredField("mSSID")
                    .apply { isAccessible = true }
                    .set(wifiInfo, "\"Headunit-Fake-Wifi\"")
            } catch (e: Exception) {}
            wifiInfo
        } catch (e: Exception) { null }
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
            delay(SELF_MODE_VPN_TIMEOUT_MS)
            if (!commManager.isConnected) {
                AppLog.w(
                    "AapService: Self Mode brought the dummy VPN up ${SELF_MODE_VPN_TIMEOUT_MS}ms " +
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


    companion object {
        /**
         * How long a Self Mode dummy VPN may stay up with no phone before it is taken down.
         *
         * stopWirelessServer() used to do this cleanup by accident, on the next mode change.
         */
        private const val SELF_MODE_VPN_TIMEOUT_MS = 120_000L
    }
}
