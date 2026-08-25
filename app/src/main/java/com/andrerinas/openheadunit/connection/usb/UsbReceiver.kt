package com.andrerinas.openheadunit.connection.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build


import com.andrerinas.openheadunit.utils.AppLog

class UsbReceiver(private val mListener: Listener)          // USB Broadcast Receiver enabled by start() & disabled by stop()
    : BroadcastReceiver() {

    init {
        AppLog.d("UsbReceiver registered")
    }

    interface Listener {
        fun onUsbDetach(device: UsbDevice)
        fun onUsbAttach(device: UsbDevice)
        fun onUsbAccessoryDetach()
        fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.i("USB Intent: $intent")

        if (action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
            mListener.onUsbAccessoryDetach()
            return
        }

        val device: UsbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return

        when (action) {
            UsbManager.ACTION_USB_DEVICE_DETACHED -> // If detach...
                mListener.onUsbDetach(device)
            // Handle detached device
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> // If attach...
                mListener.onUsbAttach(device)
            ACTION_USB_DEVICE_PERMISSION -> {
                // If Our App specific Intent for permission request...
                val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val connect = intent.getBooleanExtra(EXTRA_CONNECT, false)
                mListener.onUsbPermission(permissionGranted, connect, device)
            }
        }
    }

    companion object {
        const val ACTION_USB_DEVICE_PERMISSION = "com.andrerinas.openheadunit" + ".ACTION_USB_DEVICE_PERMISSION"
        const val EXTRA_CONNECT = "EXTRA_CONNECT"

        fun createPermissionPendingIntent(context: Context): PendingIntent {
            return PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_DEVICE_PERMISSION).apply {
                    setPackage(context.packageName)
                },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        fun createFilter(): IntentFilter {
            val filter = IntentFilter()
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            filter.addAction(ACTION_USB_DEVICE_PERMISSION)
            return filter
        }

        fun match(action: String): Boolean {
            return when (action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> true
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> true
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> true
                ACTION_USB_DEVICE_PERMISSION -> true
                else -> false
            }
        }
    }
}
