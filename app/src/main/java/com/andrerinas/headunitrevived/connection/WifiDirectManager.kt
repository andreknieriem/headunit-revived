package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.andrerinas.headunitrevived.utils.AppLog

class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isGroupOwner = false

    init {
        manager?.let { mgr ->
            val c = mgr.initialize(context, context.mainLooper, null)
            channel = c
            
            // Only use requestDeviceInfo on Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mgr.requestDeviceInfo(c, object : WifiP2pManager.DeviceInfoListener {
                    override fun onDeviceInfoAvailable(device: WifiP2pDevice?) {
                        device?.let {
                            AppLog.i("WifiDirectManager: Local Device Name: ${it.deviceName}")
                        }
                    }
                })
            } else {
                AppLog.i("WifiDirectManager: Pre-Android 10 device. Name logging via requestDeviceInfo not available.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun makeVisible() {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            AppLog.e("WifiDirectManager: P2P Manager not available.")
            return
        }

        // Try to set a custom device name via reflection (Works on many pre-Android 10 devices)
        try {
            val method = mgr.javaClass.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(mgr, ch, "HURev-Tablet", object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.i("WifiDirectManager: Device name changed to HURev-Tablet") }
                override fun onFailure(reason: Int) { AppLog.w("WifiDirectManager: Failed to change device name: $reason") }
            })
        } catch (e: Exception) {
            AppLog.w("WifiDirectManager: Reflection setDeviceName failed: ${e.message}")
        }

        // Reset state
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createNewGroup() }
            override fun onFailure(reason: Int) { createNewGroup() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup() {
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: P2P Group created successfully. Tablet is now visible.")
                isGroupOwner = true
                
                // Active discovery to be "loud" in the air
                manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { AppLog.d("WifiDirectManager: Discovery started.") }
                    override fun onFailure(reason: Int) { AppLog.w("WifiDirectManager: Discovery failed: $reason") }
                })
            }

            override fun onFailure(reason: Int) {
                AppLog.e("WifiDirectManager: Failed to create P2P group. Reason: $reason")
            }
        })
    }

    fun stop() {
        if (isGroupOwner && manager != null && channel != null) {
            manager.removeGroup(channel, null)
        }
    }
}
