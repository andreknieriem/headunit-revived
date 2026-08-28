package com.andrerinas.openheadunit.app

import android.os.Build

object ActivityLaunchPolicy {

    enum class LaunchStrategy { DIRECT, OVERLAY, NOTIFICATION }

    /**
     * Chooses the safest activity-start strategy for the given API level.
     *
     * On Android 10+ (API 29+), direct startActivity() from a background Service is
     * silently blocked by the OS. The overlay trampoline bypasses this if the
     * SYSTEM_ALERT_WINDOW permission is granted; otherwise a full-screen notification
     * is used as fallback.
     */
    fun chooseLaunchStrategy(apiLevel: Int, canDrawOverlays: Boolean): LaunchStrategy = when {
        apiLevel < Build.VERSION_CODES.Q -> LaunchStrategy.DIRECT
        canDrawOverlays -> LaunchStrategy.OVERLAY
        else -> LaunchStrategy.NOTIFICATION
    }
}
