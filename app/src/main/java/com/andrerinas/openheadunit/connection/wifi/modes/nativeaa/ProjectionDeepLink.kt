package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.aap.protocol.proto.Wireless

/**
 * Builds Android Auto's own wireless-setup deep link, the URL behind the QR code a real head unit
 * shows during pairing.
 *
 * This is how a head unit whose Bluetooth the phone cannot reach gets provisioned. There is no
 * RFCOMM channel to carry a WifiVersionRequest, so the TCP endpoint has to arrive another way, and
 * the phone accepts this one from any app because its resolver is exported. Scanning it once writes
 * the network and the endpoint into the phone's known-car record; every later connection then
 * starts on its own when the named Bluetooth device connects.
 *
 * The Bluetooth address is an identity, not a route. The phone requires the device it names to be
 * connected, but never checks that it is this head unit, so a unit with no usable adapter of its
 * own can name one the phone is already connected to.
 *
 * Pure, and tested: a field the phone rejects costs the user a scan that silently does nothing.
 */
object ProjectionDeepLink {

    /** The host and path Gearhead's resolver claims, with autoVerify, in its manifest. */
    private const val BASE_URL = "https://androidauto.com/projection/setup"

    /**
     * Bluetooth names the phone reads as a dongle rather than a head unit, which routes it into a
     * path expecting a dongle-associated car. Taken from the defaults of the flags that hold them.
     */
    private val DONGLE_NAME_MARKERS = listOf("Intercooler", "AndroidAuto-")
    private val DONGLE_NAME_PATTERN = Regex("AAWireless-.*")

    /** Gearhead's own check, and the reason a lowercase or shortened address is refused. */
    private val BSSID_PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /** Why a link could not be built, in the user's terms rather than the protocol's. */
    sealed class Invalid(val reason: String) {
        object NoSsid : Invalid("This head unit's network name is not known yet.")
        object NoPasskey : Invalid("This head unit's network password is not known yet.")
        object NoBssid : Invalid("This head unit's WiFi MAC address could not be read. Android Auto refuses a network it cannot pin to one.")
        object NoAddress : Invalid("This head unit's own IP address on that network is not known yet.")
        object NoBluetoothDevice : Invalid("No connected Bluetooth device to identify this head unit by.")
    }

    /** A built link, or why it could not be. */
    sealed class Result {
        data class Ok(val url: String) : Result()
        data class Failed(val invalid: Invalid) : Result()
    }

    /**
     * @param bluetoothMac the address of a device the phone stays connected to. On a unit with a
     *   working adapter that is this head unit; on one whose Bluetooth is an external module, it is
     *   that module, which the phone already holds a connection to for calls.
     */
    fun build(
        ssid: String,
        passkey: String,
        bssid: String,
        wppTcpIp: String,
        wppTcpPort: Int,
        bluetoothMac: String
    ): Result {
        if (ssid.isBlank()) return Result.Failed(Invalid.NoSsid)
        if (passkey.isBlank()) return Result.Failed(Invalid.NoPasskey)
        if (!BSSID_PATTERN.matches(bssid)) return Result.Failed(Invalid.NoBssid)
        if (wppTcpIp.isBlank() || wppTcpIp == WppMessages.GATEWAY_ADDRESS) {
            // The gateway form is fine in a version request, where the phone is already on our
            // network and can resolve it. Here it has nothing to resolve it against yet.
            return Result.Failed(Invalid.NoAddress)
        }
        if (!BSSID_PATTERN.matches(bluetoothMac)) return Result.Failed(Invalid.NoBluetoothDevice)

        val data = Wireless.ProjectionDeepLinkData.newBuilder()
            .setSsid(ssid)
            .setBssid(bssid.lowercase())
            .setPasskey(passkey)
            .setWppTcpIp(wppTcpIp)
            .setWppTcpPort(wppTcpPort)
            .setBluetoothMac(bluetoothMac.uppercase())
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .build()

        return Result.Ok("$BASE_URL?data=${base64Url(data.toByteArray())}")
    }

    /** The url-safe base64 alphabet, RFC 4648 section 5. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * Url-safe, unpadded base64.
     *
     * Written out rather than taken from a library: android.util.Base64 cannot run in a unit test,
     * and java.util.Base64 needs API 26 while this flavor's minSdk is 16. The phone reads this
     * straight out of a query parameter, so padding and line breaks would both need escaping and it
     * does not unescape them.
     */
    private fun base64Url(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[n ushr 18 and 0x3F])
                .append(ALPHABET[n ushr 12 and 0x3F])
                .append(ALPHABET[n ushr 6 and 0x3F])
                .append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xFF shl 16
                out.append(ALPHABET[n ushr 18 and 0x3F]).append(ALPHABET[n ushr 12 and 0x3F])
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
                out.append(ALPHABET[n ushr 18 and 0x3F])
                    .append(ALPHABET[n ushr 12 and 0x3F])
                    .append(ALPHABET[n ushr 6 and 0x3F])
            }
        }
        return out.toString()
    }

    /**
     * Whether a Bluetooth name would be read as a dongle. Such a name sends the phone down a path
     * that expects a dongle-associated car, so a unit carrying one cannot be the identity here.
     */
    fun looksLikeDongle(name: String?): Boolean {
        val n = name ?: return false
        return DONGLE_NAME_MARKERS.any { n.contains(it) } || DONGLE_NAME_PATTERN.matches(n)
    }
}
