package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog

class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isGroupOwner = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }
                device?.let {
                    AppLog.i("WifiDirectManager: Local name detected: ${it.deviceName}")
                    AapService.wifiDirectName.value = it.deviceName
                }
            }
        }
    }

    init {
        val hasP2p = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)
        
        if (hasP2p) {
            manager?.let { mgr ->
                val ch = mgr.initialize(context, context.mainLooper, null)
                channel = ch
                
                ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

                // Initial request for older devices
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mgr.requestDeviceInfo(ch) { device ->
                        device?.let { AapService.wifiDirectName.value = it.deviceName }
                    }
                }
            }
        } else {
            AppLog.w("WifiDirectManager: Hardware does not support Wi-Fi Direct (P2P)")
            AapService.wifiDirectName.value = null
        }
    }

    @SuppressLint("MissingPermission")
    fun makeVisible() {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) return

        // Reflection Hack to set name
        try {
            val method = mgr.javaClass.getMethod("setDeviceName", WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java)
            method.invoke(mgr, ch, "HURev", object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.i("WifiDirectManager: Name changed to HURev") }
                override fun onFailure(reason: Int) {}
            })
        } catch (e: Exception) {}

        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createNewGroup() }
            override fun onFailure(reason: Int) { createNewGroup() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup() {
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isGroupOwner = true
                manager?.discoverPeers(channel, null)
            }
            override fun onFailure(reason: Int) {
                AppLog.w("WifiDirectManager: createGroup failed with reason: $reason")
            }
        })
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        if (isGroupOwner && manager != null && channel != null) {
            manager.removeGroup(channel, null)
        }
    }
}
