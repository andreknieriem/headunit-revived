package com.andrerinas.openheadunit.connection.projection

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat

abstract class AbstractUsbProjectionConnection(
    protected val usbMgr: UsbManager, protected val device: UsbDevice
) : ProjectionConnection {

    @Volatile protected var usbDeviceConnection: UsbDeviceConnection? = null
    protected val stateLock = Any()

    fun isDeviceRunning(device: UsbDevice): Boolean {
        synchronized(stateLock) {
            if (!isConnected)
                return false

            return UsbDeviceCompat.getUniqueName(device) == UsbDeviceCompat.getUniqueName(this.device)
        }
    }
}
