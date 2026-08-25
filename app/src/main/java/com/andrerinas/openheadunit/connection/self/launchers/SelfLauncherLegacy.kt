package com.andrerinas.openheadunit.connection.self.launchers

import android.content.Intent
import android.os.Build
import com.andrerinas.openheadunit.connection.self.SelfLauncher
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager.Companion.AA_PACKAGE
import com.andrerinas.openheadunit.connection.self.SelfLauncherServices
import com.andrerinas.openheadunit.utils.AppLog

class SelfLauncherLegacy(
    manager: SelfLauncherManager,
    services: SelfLauncherServices) : SelfLauncher(manager, services) {

    override val name = "v17.3 and older"

    override suspend fun run(): Boolean {
        services.runWifiLauncher()

        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            services.connectivityManager.activeNetwork else null
        val networkToUse = activeNetwork ?: services.fakeNetwork
        val fakeWifiInfo = services.fakeWifiInfo

        val magicalIntent = Intent().apply {
            setClassName(
                AA_PACKAGE,
                "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
            putExtra("PARAM_SERVICE_PORT", 5288)
            networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
            fakeWifiInfo?.let { putExtra("wifi_info", it) }
        }

        AppLog.i("SelfMode: Launching AA Wireless Startup via Activity...")
        services.aap.startActivity(magicalIntent)
        return true
    }
}
