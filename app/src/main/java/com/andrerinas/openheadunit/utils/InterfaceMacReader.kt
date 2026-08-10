package com.andrerinas.openheadunit.utils

import java.io.File

/**
 * Reads an interface's MAC from outside the framework, for when
 * `NetworkInterface.getHardwareAddress()` returns null or a placeholder — the norm for ordinary
 * apps since Android 6.0.
 *
 * Separate from `WifiDirectManager.getMacFromShell()` on purpose: that one carries `[BUG_FIX]`
 * annotations and a six-deep chain tuned against specific hardware, and sharing it would risk the
 * WiFi Direct path to serve the hotspot one. ~30 duplicated lines, minus its leak — it never
 * destroys the `ip link` process or closes its streams, and this runs on a poll loop.
 */
object InterfaceMacReader {

    private val PLACEHOLDERS = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /** The MAC of [iface] from sysfs, falling back to `ip link`, or null if neither yields a real one. */
    fun read(iface: String): String? = fromSysfs(iface) ?: fromIpLink(iface)

    /** `/sys/class/net/<iface>/address`. Readable without root on most head units. */
    fun fromSysfs(iface: String): String? = try {
        val file = File("/sys/class/net/$iface/address")
        if (file.exists()) usableOrNull(file.readText()) else null
    } catch (e: Exception) {
        AppLog.d("InterfaceMacReader: sysfs read for $iface failed: ${e.message}")
        null
    }

    /** `ip link show <iface>`, parsed for its `link/ether` line. */
    fun fromIpLink(iface: String): String? {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("ip", "link", "show", iface))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val match = Regex("link/ether (([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})").find(output)
            usableOrNull(match?.groupValues?.get(1))
        } catch (e: Exception) {
            AppLog.d("InterfaceMacReader: 'ip link show $iface' failed: ${e.message}")
            null
        } finally {
            // An undestroyed Process keeps its file descriptors, and on some ROMs its zombie,
            // for the life of the app.
            try { process?.errorStream?.close() } catch (e: Exception) {}
            try { process?.outputStream?.close() } catch (e: Exception) {}
            try { process?.destroy() } catch (e: Exception) {}
        }
    }

    /** [mac] normalised to lower case, or null if it is blank or a masking placeholder. */
    private fun usableOrNull(mac: String?): String? {
        val trimmed = mac?.trim()?.lowercase().orEmpty()
        return if (trimmed.isEmpty() || trimmed in PLACEHOLDERS) null else trimmed
    }
}
