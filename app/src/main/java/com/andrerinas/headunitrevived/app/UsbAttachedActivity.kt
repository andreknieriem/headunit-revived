package com.andrerinas.headunitrevived.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.DeviceIntent
import com.andrerinas.headunitrevived.utils.Settings

/**
 * @author algavris
 * *
 * @date 30/05/2016.
 */
class UsbAttachedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLog.i("USB Intent: $intent")

        val device = DeviceIntent(intent).device
        if (device == null) {
            AppLog.e("No USB device")
            finish()
            return
        }

        if (App.provide(this).transport.isAlive) {
            AppLog.e("Thread already running")
            finish()
            return
        }

        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            AppLog.e("Usb in accessory mode")
            startService(AapService.createIntent(device, this))
            finish()
            return
        }

        val deviceCompat = UsbDeviceCompat(device)
        val settings = Settings(this)
        if (!settings.isConnectingDevice(deviceCompat)) {
            AppLog.i("Skipping device " + deviceCompat.uniqueName)
            finish()
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val usbMode = UsbAccessoryMode(usbManager)
        AppLog.i("Switching USB device to accessory mode " + deviceCompat.uniqueName)
        Toast.makeText(this, "Switching USB device to accessory mode " + deviceCompat.uniqueName, Toast.LENGTH_SHORT).show()
        if (usbMode.connectAndSwitch(device)) {
            Toast.makeText(this, "Success", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val device = DeviceIntent(getIntent()).device
        if (device == null) {
            AppLog.e("No USB device")
            finish()
            return
        }

        AppLog.i(UsbDeviceCompat.getUniqueName(device))

        if (!App.provide(this).transport.isAlive) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                AppLog.e("Usb in accessory mode")
                startService(AapService.createIntent(device, this))
            }
        } else {
            AppLog.e("Thread already running")
        }

        finish()
    }
}
