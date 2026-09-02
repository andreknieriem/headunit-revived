package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionQrPolicyTest {

    private fun snapshot(
        strategy: NativeStrategy = NativeStrategy.HOTSPOT,
        ssid: String? = "OpenHeadunit",
        passkey: String? = "12345678",
        bssid: String? = "AA:BB:CC:DD:EE:FF",
        ip: String? = "192.168.43.1",
        listeningPort: Int? = 5299,
        bluetoothMac: String? = "11:22:33:44:55:66",
        bluetoothName: String? = "Open Headunit",
    ) = ProjectionQrSnapshot(
        strategy = strategy,
        ssid = ssid,
        passkey = passkey,
        bssid = bssid,
        ip = ip,
        listeningPort = listeningPort,
        bluetoothMac = bluetoothMac,
        bluetoothName = bluetoothName,
    )

    private fun refusalOf(snapshot: ProjectionQrSnapshot?): ProjectionQrPolicy.Refusal {
        val result = ProjectionQrPolicy.decide(snapshot)
        assertTrue("expected a refusal, got $result", result is ProjectionQrPolicy.Result.Refuse)
        return (result as ProjectionQrPolicy.Result.Refuse).refusal
    }

    @Test
    fun `builds a link from a resolved hotspot session`() {
        val result = ProjectionQrPolicy.decide(snapshot())

        assertTrue(result is ProjectionQrPolicy.Result.Show)
        val url = (result as ProjectionQrPolicy.Result.Show).url
        assertTrue(url, url.startsWith("https://androidauto.com/projection/setup?data="))
        val expected = ProjectionDeepLink.build(
            ssid = "OpenHeadunit",
            passkey = "12345678",
            bssid = "AA:BB:CC:DD:EE:FF",
            wppTcpIp = "192.168.43.1",
            wppTcpPort = 5299,
            bluetoothMac = "11:22:33:44:55:66",
        )
        assertEquals((expected as ProjectionDeepLink.Result.Ok).url, url)
    }

    @Test
    fun `no snapshot means the mode is not running`() {
        assertEquals(ProjectionQrPolicy.Refusal.NOT_RUNNING, refusalOf(null))
    }

    @Test
    fun `refuses WiFi Direct, whose network is renamed on every create`() {
        assertEquals(
            ProjectionQrPolicy.Refusal.NOT_HOTSPOT,
            refusalOf(snapshot(strategy = NativeStrategy.WIFI_DIRECT))
        )
    }

    @Test
    fun `refuses while nothing is listening on the port the record would name`() {
        assertEquals(
            ProjectionQrPolicy.Refusal.NOT_LISTENING,
            refusalOf(snapshot(listeningPort = null))
        )
    }

    @Test
    fun `the port comes from the snapshot, not from the default`() {
        val result = ProjectionQrPolicy.decide(snapshot(listeningPort = 6000))
        val onDefaultPort = ProjectionQrPolicy.decide(snapshot(listeningPort = 5299))

        assertTrue(result is ProjectionQrPolicy.Result.Show)
        assertTrue(onDefaultPort is ProjectionQrPolicy.Result.Show)
        assertTrue(
            (result as ProjectionQrPolicy.Result.Show).url !=
                (onDefaultPort as ProjectionQrPolicy.Result.Show).url
        )
    }

    @Test
    fun `a masked Bluetooth address is no identity`() {
        assertEquals(
            ProjectionQrPolicy.Refusal.NO_BLUETOOTH_IDENTITY,
            refusalOf(snapshot(bluetoothMac = "02:00:00:00:00:00"))
        )
        assertEquals(
            ProjectionQrPolicy.Refusal.NO_BLUETOOTH_IDENTITY,
            refusalOf(snapshot(bluetoothMac = null))
        )
    }

    @Test
    fun `a dongle name cannot be the identity`() {
        assertEquals(
            ProjectionQrPolicy.Refusal.DONGLE_IDENTITY,
            refusalOf(snapshot(bluetoothName = "AAWireless-1234"))
        )
    }

    @Test
    fun `names what the access point has not resolved yet`() {
        assertEquals(ProjectionQrPolicy.Refusal.NO_CREDENTIALS, refusalOf(snapshot(ssid = null)))
        assertEquals(ProjectionQrPolicy.Refusal.NO_CREDENTIALS, refusalOf(snapshot(passkey = "")))
        assertEquals(ProjectionQrPolicy.Refusal.NO_BSSID, refusalOf(snapshot(bssid = null)))
        assertEquals(ProjectionQrPolicy.Refusal.NO_ADDRESS, refusalOf(snapshot(ip = null)))
    }

    @Test
    fun `the gateway address is not an address the phone can resolve yet`() {
        assertEquals(
            ProjectionQrPolicy.Refusal.NO_ADDRESS,
            refusalOf(snapshot(ip = WppMessages.GATEWAY_ADDRESS))
        )
    }
}
