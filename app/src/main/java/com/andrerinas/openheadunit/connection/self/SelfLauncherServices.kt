package com.andrerinas.openheadunit.connection.self

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import kotlinx.coroutines.delay

class SelfLauncherServices(
    val aap: AapService,
    val wifiLauncherManager: WifiLauncherManager
) {
    val connectivityManager by lazy { aap.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    val fakeNetwork by lazy { createFakeNetwork(0) }
    val fakeWifiInfo by lazy { createFakeWifiInfo() }

    private var isWifiLauncherActive = false


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

    suspend fun runWifiLauncher() {
        if (isWifiLauncherActive)
            return
        isWifiLauncherActive = true

        wifiLauncherManager.setActive(WifiLauncherMode.NATIVE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && connectivityManager.activeNetwork == null) {
            // Wait up to 1 second for the Dummy VPN to become the active network
            for (i in 1..10) {
                if (connectivityManager.activeNetwork != null) break
                delay(100)
            }
        }
    }
}
