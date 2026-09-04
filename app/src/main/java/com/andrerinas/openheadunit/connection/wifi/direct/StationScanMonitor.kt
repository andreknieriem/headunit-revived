package com.andrerinas.openheadunit.connection.wifi.direct

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Counts this unit's own WiFi scans and summarises them into the log.
 *
 * Its own receiver rather than a case in AapService's wake receiver, whose `else` arm treats any
 * unrecognised action as an OEM ignition intent and would start acting on scan results.
 */
class StationScanMonitor {

    private val scanTimes = mutableListOf<Long>()
    private var windowStartMs = 0L
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            record(System.currentTimeMillis())
        }
    }

    fun start(context: Context) {
        if (registered) return
        try {
            // EXPORTED, like the other system WiFi broadcast this service listens for: from
            // API 34 an unflagged registration throws, and NOT_EXPORTED would not hear the
            // platform.
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            registered = true
            windowStartMs = System.currentTimeMillis()
            scanTimes.clear()
        } catch (e: Exception) {
            AppLog.w("StationScanMonitor: could not watch for WiFi scans: ${e.message}")
        }
    }

    fun stop(context: Context) {
        if (!registered) return
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            AppLog.d("StationScanMonitor: receiver was already gone: ${e.message}")
        }
        registered = false
        scanTimes.clear()
        windowStartMs = 0L
    }

    /** Split out from [receiver] so the windowing can be exercised without a broadcast. */
    @Synchronized
    fun record(nowMs: Long) {
        if (windowStartMs == 0L) windowStartMs = nowMs
        scanTimes.add(nowMs)

        val elapsed = nowMs - windowStartMs
        if (elapsed < StationScanCadencePolicy.WINDOW_MS) return

        StationScanCadencePolicy.summarise(scanTimes.toList(), elapsed)
            ?.let { AppLog.i("StationScanMonitor: $it") }
        scanTimes.clear()
        windowStartMs = nowMs
    }
}
