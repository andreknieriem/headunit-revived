package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessRearmPolicyTest {

    private fun config(
        mode: WifiLauncherMode = WifiLauncherMode.NATIVE,
        helper: HelperStrategy = HelperStrategy.NEARBY_DEVICES,
        native: NativeStrategy = NativeStrategy.WIFI_DIRECT,
        bluetoothService: String = "bluetooth_manager",
    ) = WirelessRearmPolicy.Config(
        wifiConnectionMode = mode,
        helperConnectionStrategy = helper,
        nativeApStrategy = native,
        bluetoothManagerServiceName = bluetoothService,
    )

    @Test
    fun `an unchanged configuration re-arms nothing`() {
        assertFalse(WirelessRearmPolicy.requiresRearm(config(), config()))
    }

    @Test
    fun `the wireless mode re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(mode = WifiLauncherMode.HELPER))
        )
    }

    @Test
    fun `the helper strategy re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(helper = HelperStrategy.WIFI_DIRECT))
        )
    }

    /** The one that was missing: a saved hotspot left the launcher hosting a P2P group. */
    @Test
    fun `the native transport re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(native = NativeStrategy.HOTSPOT))
        )
    }

    @Test
    fun `the Bluetooth service name re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(bluetoothService = "syu_bt"))
        )
    }
}
