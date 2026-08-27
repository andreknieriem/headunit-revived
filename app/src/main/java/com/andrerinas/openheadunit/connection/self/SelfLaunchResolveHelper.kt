package com.andrerinas.openheadunit.connection.self

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils

class SelfLaunchResolveHelper(private val service: AapService) {

    fun run() {
        openPermissionsCheck()
    }

    private fun openPermissionsCheck() {
        PermissionTrampolineActivity.launch(service) { permissionCheckRan ->
            if (permissionCheckRan) {
                // AA's own permission screen ran and came back, so permissions are not why Self
                // Mode timed out. Nothing more specific is knowable from here.
                AppLog.w("SelfMode: AA's permissions are in order; the launch timed out for another reason.")
                ToastUtils.showToast(
                    service,
                    service.getString(R.string.failed_start_android_auto),
                    Toast.LENGTH_LONG
                )
            } else {
                AppLog.e("SelfMode: AA's permission activity could not be started.")
                onPermissionsCheckFailed()
            }
        }
    }

    /** Why AA's own permission screen would not open. Only reachable when it did not run at all. */
    private fun onPermissionsCheckFailed() {
        // is AA even installed?
        if (!isAAInstalled()) {
            ToastUtils.showToast(
                service,
                service.getString(R.string.failed_aa_not_installed),
                Toast.LENGTH_LONG
            )
            return
        }

        // AA isn't a system app
        if (!isAASystemApp()) {
            ToastUtils.showToast(
                service,
                service.getString(R.string.failed_aa_not_system),
                Toast.LENGTH_LONG
            )
            return
        }

        // idk why it failed :(
        ToastUtils.showToast(
            service,
            service.getString(R.string.failed_start_android_auto),
            Toast.LENGTH_LONG
        )
    }

    private fun isAAInstalled(): Boolean {
        return try {
            val pm = service.packageManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(SelfLauncherManager.AA_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(SelfLauncherManager.AA_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isAASystemApp(): Boolean {
        return try {
            val pm = service.packageManager

            // Get ApplicationInfo based on Android version
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(SelfLauncherManager.AA_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(SelfLauncherManager.AA_PACKAGE, 0)
            }

            // Check if it's a factory system app OR an updated system app
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            isSystem || isUpdatedSystem

        } catch (e: PackageManager.NameNotFoundException) {
            // App is not installed at all
            false
        }
    }
}
