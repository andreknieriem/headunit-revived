package com.andrerinas.openheadunit.connection.projection

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbNative
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class LibusbProjectionConnection(usbMgr: UsbManager,
                                 device: UsbDevice
) : AbstractUsbProjectionConnection(usbMgr, device) {

    @Volatile
    override var isConnected = false
    @Volatile private var isConnecting = false
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var usbNative: UsbNative? = null

    // Direct ByteBuffer for JNI and tracking leftover state
    private val readBuffer = ByteBuffer.allocateDirect(16384)
    private var leftoverSize = 0
    private var leftoverPos = 0

    private val activeTransfers = AtomicInteger(0)

    override val type = ProjectionConnection.Type.USB

    override val isSingleMessage: Boolean
        get() = false

    override suspend fun connect() = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            if (isConnected || isConnecting) {
                return@withContext false
            }
            isConnecting = true
        }

        try {
            if (!usbMgr.hasPermission(device)) {
                AppLog.e("LibusbAccessoryConnection: No permission for USB device")
                synchronized(stateLock) { isConnecting = false }
                return@withContext false
            }

            // Open device
            var conn: UsbDeviceConnection? = null
            for (i in 0 until 3) {
                if (!isConnecting) {
                    conn?.close()
                    return@withContext false
                }
                try {
                    conn = usbMgr.openDevice(device)
                    if (conn != null) break
                } catch (t: Throwable) {
                    AppLog.w("LibusbAccessoryConnection: Attempt ${i + 1} to openDevice failed: ${t.message}")
                }
                if (i < 2) {
                    try {
                        for (k in 0 until 10) {
                            if (!isConnecting) {
                                conn?.close()
                                return@withContext false
                            }
                            Thread.sleep(100)
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            if (conn == null) {
                AppLog.e("LibusbAccessoryConnection: connection is null")
                synchronized(stateLock) { isConnecting = false }
                return@withContext false
            }

            if (!isConnecting) {
                conn.close()
                return@withContext false
            }

            synchronized(stateLock) {
                usbDeviceConnection = conn
            }

            if (device.interfaceCount <= 0) {
                AppLog.e("LibusbAccessoryConnection: No interface found on device")
                synchronized(stateLock) {
                    conn.close()
                    usbDeviceConnection = null
                    isConnecting = false
                }
                return@withContext false
            }
            val iface = device.getInterface(0)
            synchronized(stateLock) {
                usbInterface = iface
            }

            if (!conn.claimInterface(iface, true)) {
                AppLog.e("LibusbAccessoryConnection: Failed to claim interface")
                synchronized(stateLock) {
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    isConnecting = false
                }
                return@withContext false
            }

            // Find endpoints, bulk preferred; the standard transport asks the same helper.
            val (epIn, epOut) = UsbDeviceCompat.selectEndpoints(iface)
            if (epIn == null || epOut == null) {
                AppLog.e("LibusbAccessoryConnection: Unable to find endpoints")
                synchronized(stateLock) {
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    isConnecting = false
                }
                return@withContext false
            }

            synchronized(stateLock) {
                endpointIn = epIn
                endpointOut = epOut
            }

            if (!isConnecting) {
                synchronized(stateLock) {
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    endpointIn = null
                    endpointOut = null
                    isConnecting = false
                }
                return@withContext false
            }

            val native = UsbNative()
            if (!native.wrap(conn, epIn.address, epOut.address)) {
                AppLog.e("LibusbAccessoryConnection: Failed to wrap USB device via JNI")
                synchronized(stateLock) {
                    native.close()
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    endpointIn = null
                    endpointOut = null
                    isConnecting = false
                }
                return@withContext false
            }

            synchronized(stateLock) {
                if (!isConnecting) {
                    native.close()
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    endpointIn = null
                    endpointOut = null
                    isConnecting = false
                    return@withContext false
                }
                usbNative = native
                isConnected = true
                isConnecting = false
            }
            AppLog.i("LibusbAccessoryConnection: Successfully connected via JNI Libusb")
            return@withContext true
        } catch (e: Exception) {
            AppLog.e("LibusbAccessoryConnection: Error during connect: ${e.message}")
            synchronized(stateLock) {
                isConnecting = false
            }
            disconnect()
            return@withContext false
        }
    }

    override fun disconnect() {
        synchronized(stateLock) {
            isConnecting = false
            isConnected = false
        }

        // Wait for active JNI transfers (readers/writers) to finish before freeing context.
        // Capped at 1500ms because the JNI read timeout chunk is capped at 1000ms.
        val start = SystemClock.elapsedRealtime()
        while (activeTransfers.get() > 0 && SystemClock.elapsedRealtime() - start < 1500) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                break
            }
        }

        synchronized(stateLock) {
            try {
                usbNative?.close()
            } catch (e: Exception) {
                AppLog.e("LibusbAccessoryConnection: Error closing native: ${e.message}")
            }
            usbNative = null

            try {
                if (usbDeviceConnection != null && usbInterface != null) {
                    usbDeviceConnection!!.releaseInterface(usbInterface)
                }
            } catch (e: Exception) {}

            try {
                usbDeviceConnection?.close()
            } catch (e: Exception) {}

            usbDeviceConnection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
            leftoverSize = 0
            leftoverPos = 0
        }
    }

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        if (!isConnected) return -1
        val native = usbNative ?: return -1
        activeTransfers.incrementAndGet()
        try {
            if (!isConnected) return -1
            return native.write(buf, length, timeout)
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        if (!isConnected) return -1
        val native = usbNative ?: return -1
        activeTransfers.incrementAndGet()
        try {
            if (!isConnected) return -1
            var totalReturned = 0
            val overallStart = SystemClock.elapsedRealtime()

            while (totalReturned < length && isConnected) {
                if (leftoverSize > 0) {
                    val available = leftoverSize - leftoverPos
                    val toCopy = minOf(length - totalReturned, available)

                    readBuffer.position(leftoverPos)
                    readBuffer.get(buf, totalReturned, toCopy)

                    leftoverPos += toCopy
                    totalReturned += toCopy

                    if (leftoverPos >= leftoverSize) {
                        leftoverSize = 0
                        leftoverPos = 0
                    }

                    if (totalReturned >= length || !readFully) break
                    continue
                }

                if (!isConnected) break

                val jniTimeout = if (timeout <= 0) {
                    1000
                } else {
                    val elapsed = SystemClock.elapsedRealtime() - overallStart
                    val remaining = timeout - elapsed
                    if (remaining <= 0) break
                    minOf(remaining.toInt(), 1000)
                }

                readBuffer.clear()
                val transferred = native.read(readBuffer, jniTimeout)
                if (transferred < 0) {
                    isConnected = false
                    return if (totalReturned > 0) totalReturned else -1
                }
                if (transferred == 0) {
                    if (timeout > 0) {
                        val elapsed = SystemClock.elapsedRealtime() - overallStart
                        if (elapsed >= timeout) {
                            break
                        }
                    }
                    continue
                }

                leftoverSize = transferred
                leftoverPos = 0
            }

            return totalReturned
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    companion object {
    }
}
