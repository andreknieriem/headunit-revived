package com.andrerinas.openheadunit.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Unified Accessibility Service that intercepts specified launcher/stock app packages
 * (such as CarbitLink, EasyConn, TLink, ZLink, com.yftech.btphone) and redirects focus to Emzoom AA.
 * Also handles double-tap Home/Menu key interception to quickly launch Emzoom AA.
 */
open class AppRedirectService : AccessibilityService() {

    private val targetPackages = setOf(
        "net.easyconn",
        "com.gpl.carbit",
        "com.easyconn",
        "com.syu.carbit"
    )

    private val callPackages = setOf(
        "com.yftech.btphone",
        "com.syu.bt",
        "com.fyt.bt",
        "com.autochips.bluetooth",
        "com.ts.bt",
        "com.sso.bt",
        "com.microntek.bluetooth"
    )

    private var lastTriggerTime = 0L
    private var lastHomeTapTimeMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app package to prevent loops
        if (packageName == applicationContext.packageName) {
            return
        }

        val context = applicationContext
        val settings = App.provide(context).settings

        // 1. Handle Call App interception
        if (callPackages.contains(packageName) && settings.raiseProjectionDuringCall) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTime > 1000) {
                lastTriggerTime = now
                AppLog.i("AppRedirectService: Intercepted call app $packageName launch. Raising projection...")

                killAndForceStopApp(context, packageName)

                val projIntent = Intent(context, AapProjectionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                try {
                    startActivity(projIntent)
                } catch (e: Exception) {
                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(mainIntent)
                }
            }
            return
        }

        // 2. Handle CarbitLink / EasyConn launcher interception
        if (!settings.redirectCarbitLink) {
            return
        }

        if (targetPackages.contains(packageName)) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTime < 2500) {
                return
            }
            lastTriggerTime = now

            AppLog.i("AppRedirectService: Detected $packageName launch. Redirecting to Emzoom AA...")

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            startActivity(launchIntent)

            Handler(Looper.getMainLooper()).postDelayed({
                killAndForceStopApp(context, packageName)
                startActivity(launchIntent)
            }, 20L)
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        val appSettings = App.provide(applicationContext).settings
        if (!appSettings.doubleTapHomeToOpen) {
            return super.onKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_MENU) {
                val now = SystemClock.uptimeMillis()
                if (now - lastHomeTapTimeMs < 450L) {
                    lastHomeTapTimeMs = 0L
                    AppLog.i("AppRedirectService: Double-tap Home detected! Opening Emzoom AA...")
                    val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(launchIntent)
                    return true // Consume second home tap
                }
                lastHomeTapTimeMs = now
            }
        }
        return super.onKeyEvent(event)
    }

    private fun killAndForceStopApp(context: Context, packageName: String) {
        try {
            val am = context.getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses(packageName)
        } catch (e: Exception) {
            AppLog.w("AppRedirectService: Failed ActivityManager kill: ${e.message}")
        }

        val suExecutor = App.provide(context).suExecutor
        Thread {
            try {
                suExecutor.execShell("appops set $packageName SYSTEM_ALERT_WINDOW ignore", false)
                suExecutor.execShell("input keyevent 4", false)
                suExecutor.execShell("am force-stop $packageName", false)
            } catch (e: Exception) {
                AppLog.w("AppRedirectService: Failed su force-stop: ${e.message}")
            }
        }.start()
    }

    override fun onInterrupt() {}

    companion object {
        fun isEnabled(context: Context): Boolean {
            val appService = "${context.packageName}/${AppRedirectService::class.java.canonicalName}"
            val carbitService = "${context.packageName}/com.andrerinas.openheadunit.service.CarbitLinkRedirectService"
            val enabledServices = SystemSettings.Secure.getString(
                context.contentResolver,
                SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(appService) || enabledServices.contains(carbitService)
        }
    }
}
