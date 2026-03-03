package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.andrerinas.headunitrevived.utils.AppLog

class WifiDirectManager(private val context: Context) {

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isGroupOwner = false

    init {
        manager?.let {
            channel = it.initialize(context, context.mainLooper, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun makeVisible() {
        if (manager == null || channel == null) {
            AppLog.e("WifiDirectManager: P2P Manager not available.")
            return
        }

        // First, clear any existing groups to start fresh
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                createNewGroup()
            }

            override fun onFailure(reason: Int) {
                // Even if it fails (no group exists), try to create one
                createNewGroup()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup() {
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: P2P Group created successfully. Tablet is now visible.")
                isGroupOwner = true
            }

            override fun onFailure(reason: Int) {
                AppLog.e("WifiDirectManager: Failed to create P2P group. Reason: $reason")
            }
        })
    }

    fun stop() {
        if (isGroupOwner && manager != null && channel != null) {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    AppLog.i("WifiDirectManager: P2P Group removed.")
                }
                override fun onFailure(reason: Int) {
                    AppLog.e("WifiDirectManager: Failed to remove P2P group.")
                }
            })
        }
    }
}
