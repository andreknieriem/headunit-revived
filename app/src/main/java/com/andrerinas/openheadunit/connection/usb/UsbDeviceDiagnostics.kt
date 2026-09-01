package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Dumps the raw USB bus alongside our own verdict on each device. Every enumeration site filters
 * with [UsbDeviceCompat.isAndroidDevice], so a user reporting "no USB device found" could mean
 * nothing was on the bus or that we rejected what was there, and the logs could not tell them
 * apart. An empty result is the case worth reporting, so it prints at INFO; otherwise Verbose.
 */
object UsbDeviceDiagnostics {

    /** Last dump per caller, so a repeated scan of an unchanged bus does not repeat itself. */
    private val lastSignatures = ConcurrentHashMap<String, String>()

    fun logDeviceList(context: Context, usbManager: UsbManager, caller: String) {
        val devices = try {
            usbManager.deviceList.values.toList()
        } catch (e: Throwable) {
            // Some head unit ROMs throw out of getDeviceList() rather than returning empty.
            AppLog.e("UsbDiagnostics: $caller could not read the USB device list: ${e.message}")
            return
        }

        val accepted = devices.count { UsbDeviceCompat.isConnectable(context, it) }
        val reportAtInfo = accepted == 0
        val lines = devices.map { "UsbDiagnostics:   ${describe(context, it, usbManager)}" }

        // The service scan runs on every attach, detach and permission result, so repeating an
        // unchanged bus would bury the change that matters in a log the user has to read. Keyed by
        // caller as well, so pressing the USB button always says what it saw.
        val signature = "$accepted/${devices.size}|" + lines.joinToString("|")
        if (lastSignatures.put(caller, signature) == signature) return

        val header = "UsbDiagnostics: $caller sees ${devices.size} USB device(s), " +
            "$accepted usable for Android Auto"
        if (reportAtInfo) AppLog.i(header) else AppLog.v(header)

        for (line in lines) {
            if (reportAtInfo) AppLog.i(line) else AppLog.v(line)
        }

        if (devices.isEmpty()) {
            // The common causes, in the order they are worth checking, so the log answers the
            // question without a round trip to the reporter.
            AppLog.i(
                "UsbDiagnostics: nothing is on the bus. Either the port carries no data, the unit " +
                    "is not in USB host mode, or a wireless adapter is waiting for its phone " +
                    "before it presents itself."
            )
        }
    }

    /** One line per device: identity, our verdict, and every interface descriptor behind it. */
    fun describe(context: Context, device: UsbDevice, usbManager: UsbManager): String {
        val sb = StringBuilder()
        sb.append(UsbDeviceCompat.getUniqueName(device))
        sb.append(" [")
        sb.append(String.format(Locale.US, "class %02X/%02X/%02X",
            device.deviceClass, device.deviceSubclass, device.deviceProtocol))
        sb.append(if (usbManager.hasPermission(device)) ", permission" else ", no permission")
        sb.append("] ")
        sb.append(UsbDeviceCompat.connectableReason(context, device))

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            sb.append(String.format(Locale.US, " | if%d %02X/%02X/%02X",
                i, iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol))
            sb.append(" ").append(endpointSummary(iface))
        }
        return sb.toString()
    }

    /**
     * Endpoint shape matters because an accessory interface needs a bulk IN and a bulk OUT. Counts
     * rather than flags: a phone in file transfer and a vendor ethernet controller both come out as
     * "bulkIn+bulkOut", and the second bulk OUT is the only thing in the shape that differs.
     */
    private fun endpointSummary(iface: UsbInterface): String {
        var bulkIn = 0
        var bulkOut = 0
        var others = 0
        for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn++ else bulkOut++
            } else {
                others++
            }
        }
        val parts = mutableListOf<String>()
        if (bulkIn > 0) parts.add("${bulkIn}xbulkIn")
        if (bulkOut > 0) parts.add("${bulkOut}xbulkOut")
        if (others > 0) parts.add("$others other")
        return if (parts.isEmpty()) "(no endpoints)" else "(${parts.joinToString("+")})"
    }
}
