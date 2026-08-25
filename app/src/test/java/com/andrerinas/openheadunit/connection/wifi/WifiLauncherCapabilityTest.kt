package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each launcher asks [WifiLauncherSharedServices] to run for it.
 *
 * These three predicates are the whole of the bring-up contract now, so a wrong answer is a route
 * that silently never starts rather than a compile error. The AAP port case below is the one that
 * regressed: gated on the native strategy, the hotspot transport bound nothing.
 */
class WifiLauncherCapabilityTest {

    @Test
    fun `the AAP port is bound on both native transports`() {
        for (strategy in NativeStrategy.entries) {
            assertTrue(
                "Native AA on $strategy must bind 5288 — its credentials name that port",
                WifiLauncherMock.create(WifiLauncherMode.NATIVE, nativeStrategy = strategy)
                    .hasWirelessServer()
            )
        }
    }

    @Test
    fun `the AAP port is bound for every helper strategy`() {
        for (strategy in HelperStrategy.entries) {
            assertTrue(
                "Helper/$strategy waits for the phone on 5288",
                WifiLauncherMock.create(WifiLauncherMode.HELPER, helperStrategy = strategy)
                    .hasWirelessServer()
            )
        }
    }

    @Test
    fun `only the P2P native transport runs a group`() {
        assertTrue(
            WifiLauncherMock.create(WifiLauncherMode.NATIVE, nativeStrategy = NativeStrategy.WIFI_DIRECT)
                .hasWifiDirect()
        )
        // False on purpose: a true here makes the caller force the hotspot off before starting
        // P2P, which would take down the very access point this transport is about to advertise.
        assertFalse(
            WifiLauncherMock.create(WifiLauncherMode.NATIVE, nativeStrategy = NativeStrategy.HOTSPOT)
                .hasWifiDirect()
        )
    }

    @Test
    fun `local discovery matches the set that registers an NSD record`() {
        assertTrue(WifiLauncherMock.create(WifiLauncherMode.AUTO).hasLocalDiscovery())
        assertFalse(WifiLauncherMock.create(WifiLauncherMode.MANUAL).hasLocalDiscovery())
        for (strategy in NativeStrategy.entries) {
            assertFalse(
                WifiLauncherMock.create(WifiLauncherMode.NATIVE, nativeStrategy = strategy)
                    .hasLocalDiscovery()
            )
        }
        val expected = setOf(
            HelperStrategy.COMMON_WIFI, HelperStrategy.PHONE_HOTSPOT, HelperStrategy.HEADUNIT_HOTSPOT
        )
        for (strategy in HelperStrategy.entries) {
            val launcher = WifiLauncherMock.create(WifiLauncherMode.HELPER, helperStrategy = strategy)
            if (strategy in expected)
                assertTrue("Helper/$strategy shares an IP network", launcher.hasLocalDiscovery())
            else
                assertFalse("Helper/$strategy has no network to advertise on", launcher.hasLocalDiscovery())
        }
    }
}
