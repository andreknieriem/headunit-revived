package com.andrerinas.openheadunit.connection.usb

import android.hardware.usb.UsbDeviceConnection
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer

class UsbNative {
    private var contextPtr: Long = 0
    private var handlePtr: Long = 0
    private var epIn: Int = 0
    private var epOut: Int = 0

    companion object {
        init {
            try {
                System.loadLibrary("usb1.0")
                System.loadLibrary("usbhelper")
                AppLog.i("UsbNative: Loaded native libraries successfully")
            } catch (e: Throwable) {
                AppLog.e("UsbNative: Failed to load native libraries: ${e.message}")
            }
        }
    }

    init {
        try {
            contextPtr = initContext()
            if (contextPtr == 0L) {
                AppLog.e("UsbNative: Failed to initialize libusb context")
            }
        } catch (e: Throwable) {
            AppLog.e("UsbNative: Exception initializing context: ${e.message}")
        }
    }

    fun wrap(connection: UsbDeviceConnection, epInAddr: Int, epOutAddr: Int): Boolean {
        epIn = epInAddr
        epOut = epOutAddr
        if (contextPtr == 0L) {
            AppLog.e("UsbNative: Cannot wrap device, context is invalid")
            return false
        }
        try {
            handlePtr = wrapDevice(contextPtr, connection.fileDescriptor)
            if (handlePtr == 0L) {
                AppLog.e("UsbNative: wrapDevice returned NULL handle")
                return false
            }
            detachKernel(handlePtr, 0)
            claimInterface(handlePtr, 0)
            AppLog.i("UsbNative: Wrapped device fd=${connection.fileDescriptor} successfully")
            return true
        } catch (e: Throwable) {
            AppLog.e("UsbNative: Exception wrapping device: ${e.message}")
            return false
        }
    }

    fun write(data: ByteArray, length: Int, timeout: Int): Int {
        if (handlePtr == 0L) return -1
        return try {
            nativeWrite(handlePtr, data, length, epOut, timeout)
        } catch (e: Throwable) {
            AppLog.e("UsbNative: Exception during write: ${e.message}")
            -1
        }
    }

    fun read(buffer: ByteBuffer, timeout: Int): Int {
        if (handlePtr == 0L) return -1
        return try {
            nativeRead(handlePtr, buffer, epIn, timeout)
        } catch (e: Throwable) {
            AppLog.e("UsbNative: Exception during read: ${e.message}")
            -1
        }
    }

    fun reset() {
        if (handlePtr != 0L) {
            try {
                nativeResetDevice(handlePtr)
            } catch (e: Throwable) {
                AppLog.e("UsbNative: Exception during reset: ${e.message}")
            }
        }
    }

    fun close() {
        if (handlePtr != 0L) {
            try {
                closeDevice(handlePtr)
            } catch (e: Throwable) {
                AppLog.e("UsbNative: Exception during closeDevice: ${e.message}")
            }
            handlePtr = 0
        }
        if (contextPtr != 0L) {
            try {
                exitContext(contextPtr)
            } catch (e: Throwable) {
                AppLog.e("UsbNative: Exception during exitContext: ${e.message}")
            }
            contextPtr = 0
        }
    }

    fun accModeSwitch(): Int {
        if (handlePtr == 0L) return -1
        return try {
            accModeSwitch(handlePtr)
        } catch (e: Throwable) {
            AppLog.e("UsbNative: Exception during accModeSwitch: ${e.message}")
            -1
        }
    }

    private external fun initContext(): Long
    private external fun wrapDevice(ctx: Long, fd: Int): Long
    private external fun detachKernel(handle: Long, iface: Int): Int
    private external fun claimInterface(handle: Long, iface: Int): Int
    private external fun nativeWrite(handle: Long, data: ByteArray, length: Int, endpoint: Int, timeout: Int): Int
    private external fun nativeRead(handle: Long, jbuf: ByteBuffer, endpoint: Int, timeout: Int): Int
    private external fun nativeResetDevice(handle: Long)
    private external fun closeDevice(handle: Long)
    private external fun exitContext(ctx: Long)
    private external fun accModeSwitch(handle: Long): Int
}
