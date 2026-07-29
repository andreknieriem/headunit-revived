package com.andrerinas.headunitrevived.app

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.MainActivity
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import android.os.UserManager
import android.os.Build

class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // Use device-protected storage so the BT MACs are readable during locked boot
        val targetMacs = Settings.getAutoStartBtMacs(context)

        if (targetMacs.isEmpty()) return
        
        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        
        // [FIX] Don't trigger auto-start if we are already connected!
        // This prevents activity restarts if BT reconnects during a session.
        if (!isLocked && com.andrerinas.headunitrevived.App.provide(context).commManager.isConnected) {
            AppLog.d("AutoStartReceiver: Already connected to Android Auto. Ignoring BT event.")
            return
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

            AppLog.i("BT Device connected: ${device?.name} (${device?.address})")

            if (device != null && targetMacs.contains(device.address)) {
                AppLog.i("MATCH! Starting AapService via Bluetooth Auto-start...")

                // Start the service to make the app alive. Explicit action so onStartCommand
                // re-arms wireless mode even if the service process was already running from
                // an earlier session (onCreate's init only runs once) — see ACTION_BT_AUTO_START.
                val serviceIntent = Intent(context, AapService::class.java).setAction(AapService.ACTION_BT_AUTO_START)
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    AppLog.e("Failed to start AapService from background: ${e.message}")
                }

                // Also attempt to start the UI (might be blocked on Android 10+ without special permission)
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "Bluetooth auto-start")
                }
                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    AppLog.w("Could not start UI from background (expected on Android 10+): ${e.message}")
                }
            }
        }
    }
}