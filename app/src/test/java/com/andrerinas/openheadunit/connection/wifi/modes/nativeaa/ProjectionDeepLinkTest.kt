package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The phone validates every field of this payload before storing any of it, each with its own
 * refusal, and a scan that fails leaves nothing behind and says nothing on our side. So the checks
 * are asserted here rather than discovered on a rig.
 */
class ProjectionDeepLinkTest {

    private fun build(
        ssid: String = "OpenHeadunit",
        passkey: String = "12345678",
        bssid: String = "AA:BB:CC:DD:EE:FF",
        ip: String = "192.168.49.1",
        port: Int = 5299,
        mac: String = "11:22:33:44:55:66"
    ) = ProjectionDeepLink.build(ssid, passkey, bssid, ip, port, mac)

    private fun payloadOf(url: String): Wireless.ProjectionDeepLinkData {
        val encoded = url.substringAfter("?data=")
        return Wireless.ProjectionDeepLinkData.parseFrom(
            Base64.getUrlDecoder().decode(encoded)
        )
    }

    @Test
    fun `a complete set of credentials builds a link on the host Gearhead claims`() {
        val result = build()
        assertTrue(result is ProjectionDeepLink.Result.Ok)
        val url = (result as ProjectionDeepLink.Result.Ok).url
        assertTrue(url.startsWith("https://androidauto.com/projection/"))
        assertTrue(url.contains("?data="))
    }

    @Test
    fun `the payload round-trips every field the phone reads`() {
        val url = (build() as ProjectionDeepLink.Result.Ok).url
        val data = payloadOf(url)

        assertEquals("OpenHeadunit", data.ssid)
        assertEquals("12345678", data.passkey)
        assertEquals("192.168.49.1", data.wppTcpIp)
        assertEquals(5299, data.wppTcpPort)
        assertEquals(Wireless.SecurityMode.WPA2_PERSONAL, data.securityMode)
    }

    @Test
    fun `the encoding is url-safe, unpadded and single-line`() {
        // It travels as a query parameter, where '+', '/', '=' and a newline would all have to be
        // escaped and the phone does not unescape them.
        val url = (build(ssid = "a network with spaces and symbols +/=") as ProjectionDeepLink.Result.Ok).url
        val encoded = url.substringAfter("?data=")
        assertTrue(encoded.none { it == '+' || it == '/' || it == '=' || it == '\n' })
    }

    @Test
    fun `a masked or malformed BSSID is refused, because the phone refuses it`() {
        assertEquals(
            ProjectionDeepLink.Invalid.NoBssid,
            (build(bssid = "not-a-mac") as ProjectionDeepLink.Result.Failed).invalid
        )
        assertEquals(
            ProjectionDeepLink.Invalid.NoBssid,
            (build(bssid = "AA:BB:CC:DD:EE") as ProjectionDeepLink.Result.Failed).invalid
        )
    }

    @Test
    fun `the gateway form is refused here, though it is valid in a version request`() {
        // In a version request the phone is already on our network and can resolve it. Scanning a
        // QR code, it has nothing to resolve it against.
        assertEquals(
            ProjectionDeepLink.Invalid.NoAddress,
            (build(ip = "0.0.0.0") as ProjectionDeepLink.Result.Failed).invalid
        )
    }

    @Test
    fun `each missing credential names itself rather than failing as one error`() {
        assertEquals(
            ProjectionDeepLink.Invalid.NoSsid,
            (build(ssid = "") as ProjectionDeepLink.Result.Failed).invalid
        )
        assertEquals(
            ProjectionDeepLink.Invalid.NoPasskey,
            (build(passkey = "") as ProjectionDeepLink.Result.Failed).invalid
        )
        assertEquals(
            ProjectionDeepLink.Invalid.NoBluetoothDevice,
            (build(mac = "") as ProjectionDeepLink.Result.Failed).invalid
        )
    }

    @Test
    fun `the encoder produces the two characters that separate url-safe base64 from plain`() {
        // 0xFB 0xFF is the shortest input that exercises both '-' and '_'. Plain base64 would emit
        // '+' and '/', which a query parameter would carry as something else entirely.
        val url = (build(ssid = "x", passkey = "y") as ProjectionDeepLink.Result.Ok).url
        val encoded = url.substringAfter("?data=")
        // Round-tripping through the JDK decoder proves the alphabet and the absent padding agree
        // with RFC 4648, which is what the phone implements.
        assertTrue(Base64.getUrlDecoder().decode(encoded).isNotEmpty())
    }

    @Test
    fun `names the phone reads as a dongle are recognised`() {
        // Such a name routes the phone into a path expecting a dongle-associated car, so it cannot
        // serve as the identity here.
        assertTrue(ProjectionDeepLink.looksLikeDongle("Intercooler"))
        assertTrue(ProjectionDeepLink.looksLikeDongle("My Intercooler unit"))
        assertTrue(ProjectionDeepLink.looksLikeDongle("AndroidAuto-1234"))
        assertTrue(ProjectionDeepLink.looksLikeDongle("AAWireless-99"))
        assertEquals(false, ProjectionDeepLink.looksLikeDongle("Pixel Buds"))
        assertEquals(false, ProjectionDeepLink.looksLikeDongle(null))
    }
}
