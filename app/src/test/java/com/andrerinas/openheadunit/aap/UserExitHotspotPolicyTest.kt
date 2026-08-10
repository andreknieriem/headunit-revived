package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserExitHotspotPolicyTest {

    private fun exit(
        mode: Int,
        strategy: Int = 2,
        transport: NativeTransport = NativeTransport.WIFI_DIRECT,
        autoEnableHotspot: Boolean = true,
        teardownProvenUnsafe: Boolean = false
    ) = UserExitHotspotPolicy.onUserExit(
        mode, strategy, transport, autoEnableHotspot, teardownProvenUnsafe
    )

    @Test
    fun `native AA on this unit's own access point takes it down when the app may drive it`() {
        assertEquals(
            HotspotExitAction.DISABLE,
            exit(mode = 3, transport = NativeTransport.HOTSPOT, autoEnableHotspot = true)
        )
    }

    @Test
    fun `the helper's head-unit-hotspot strategy is the same route by another name`() {
        assertEquals(
            HotspotExitAction.DISABLE,
            exit(mode = 2, strategy = 4, autoEnableHotspot = true)
        )
    }

    @Test
    fun `an access point the app was never given charge of is left up, not switched off`() {
        // Switching one back on is best effort and usually impossible without WRITE_SETTINGS, so a
        // hotspot the user turned on by hand could be left off with nothing able to restore it.
        assertEquals(
            HotspotExitAction.WARN_LEFT_UP,
            exit(mode = 3, transport = NativeTransport.HOTSPOT, autoEnableHotspot = false)
        )
        assertEquals(
            HotspotExitAction.WARN_LEFT_UP,
            exit(mode = 2, strategy = 4, autoEnableHotspot = false)
        )
    }

    @Test
    fun `WiFi Direct routes are never this policy's to answer`() {
        // The caller removes the P2P group on these; both branches firing for one disconnect would
        // tear down the group and the hotspot at once.
        assertEquals(
            HotspotExitAction.NONE,
            exit(mode = 3, transport = NativeTransport.WIFI_DIRECT, autoEnableHotspot = true)
        )
        assertEquals(HotspotExitAction.NONE, exit(mode = 2, strategy = 1, autoEnableHotspot = true))
    }

    @Test
    fun `the helper's other strategies and the server mode are left alone`() {
        for (strategy in listOf(0, 2, 3)) {
            assertEquals(
                "helper strategy $strategy hosts no access point",
                HotspotExitAction.NONE,
                exit(mode = 2, strategy = strategy)
            )
        }
        assertEquals(HotspotExitAction.NONE, exit(mode = 1, strategy = 0))
    }

    @Test
    fun `the transport only speaks for native mode`() {
        // Mode 2 keeps its own selector; reading the native transport there would make strategy 4
        // depend on a setting that has no meaning in that mode.
        assertEquals(
            HotspotExitAction.DISABLE,
            exit(mode = 2, strategy = 4, transport = NativeTransport.WIFI_DIRECT)
        )
        assertEquals(
            HotspotExitAction.NONE,
            exit(mode = 2, strategy = 0, transport = NativeTransport.HOTSPOT)
        )
    }

    @Test
    fun `a device that could not switch its access point back on is not asked to again`() {
        // Learned the only way it can be — by trying once and watching it fail. From then on the
        // hotspot is left alone even though the user gave the app charge of it, because on such a
        // device taking it down costs them a hotspot per session and never buys the phone leaving.
        assertEquals(
            HotspotExitAction.WARN_LEFT_UP,
            exit(
                mode = 3,
                transport = NativeTransport.HOTSPOT,
                autoEnableHotspot = true,
                teardownProvenUnsafe = true
            )
        )
    }

    @Test
    fun `the flag does not make this policy claim a disconnect that was never its own`() {
        // It withdraws; it never reaches. A WiFi Direct disconnect stays WiFi Direct's.
        assertEquals(
            HotspotExitAction.NONE,
            exit(
                mode = 3,
                transport = NativeTransport.WIFI_DIRECT,
                autoEnableHotspot = true,
                teardownProvenUnsafe = true
            )
        )
        assertEquals(
            HotspotExitAction.NONE,
            exit(mode = 1, autoEnableHotspot = true, teardownProvenUnsafe = true)
        )
    }

    @Test
    fun `an untried device is still asked, so the flag costs nothing until it is earned`() {
        assertEquals(
            HotspotExitAction.DISABLE,
            exit(
                mode = 3,
                transport = NativeTransport.HOTSPOT,
                autoEnableHotspot = true,
                teardownProvenUnsafe = false
            )
        )
    }

    @Test
    fun `this policy and usesWifiDirect never both claim the same disconnect`() {
        for (mode in 1..3) {
            for (strategy in 0..4) {
                for (transport in NativeTransport.values()) {
                    val hotspot = UserExitHotspotPolicy.usesHeadUnitHotspot(mode, strategy, transport)
                    val p2p = WifiModePolicy.usesWifiDirect(mode, strategy, transport)
                    assertFalse("mode=$mode strategy=$strategy transport=$transport", hotspot && p2p)
                }
            }
        }
        // Not vacuous: each does claim something.
        assertTrue(UserExitHotspotPolicy.usesHeadUnitHotspot(3, 0, NativeTransport.HOTSPOT))
        assertTrue(WifiModePolicy.usesWifiDirect(3, 0, NativeTransport.WIFI_DIRECT))
    }
}
