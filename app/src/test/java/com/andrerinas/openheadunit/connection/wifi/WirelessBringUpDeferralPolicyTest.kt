package com.andrerinas.openheadunit.connection.wifi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the narrow trigger. A wireless-only unit and a unit with a permanently attached peripheral
 * must both arm wireless exactly as they did before this existed.
 */
class WirelessBringUpDeferralPolicyTest {

    @Test
    fun `an empty bus arms wireless immediately`() {
        assertFalse(
            WirelessBringUpDeferralPolicy.shouldDefer(
                accessoryDeviceOnBus = false,
                switchInFlight = false,
                msSinceFirstDeferral = 0L,
            )
        )
    }

    /**
     * The regression this policy is most likely to cause: an audio or Bluetooth adapter left
     * plugged in is neither of the two things asked about, so it costs nothing.
     */
    @Test
    fun `a peripheral that is not in accessory mode and not switching defers nothing`() {
        assertFalse(
            WirelessBringUpDeferralPolicy.shouldDefer(
                accessoryDeviceOnBus = false,
                switchInFlight = false,
                msSinceFirstDeferral = 4_000L,
            )
        )
    }

    @Test
    fun `an accessory-mode device defers the bring-up`() {
        assertTrue(
            WirelessBringUpDeferralPolicy.shouldDefer(
                accessoryDeviceOnBus = true,
                switchInFlight = false,
                msSinceFirstDeferral = 0L,
            )
        )
    }

    @Test
    fun `a switch in flight defers the bring-up before the device re-enumerates`() {
        assertTrue(
            WirelessBringUpDeferralPolicy.shouldDefer(
                accessoryDeviceOnBus = false,
                switchInFlight = true,
                msSinceFirstDeferral = 0L,
            )
        )
    }

    @Test
    fun `the budget releases a dongle that never negotiates`() {
        assertTrue(
            WirelessBringUpDeferralPolicy.shouldDefer(
                accessoryDeviceOnBus = true,
                switchInFlight = true,
                msSinceFirstDeferral = WirelessBringUpDeferralPolicy.DEFER_BUDGET_MS - 1,
            )
        )
        assertFalse(
            WirelessBringUpDeferralPolicy.shouldDefer(
                accessoryDeviceOnBus = true,
                switchInFlight = true,
                msSinceFirstDeferral = WirelessBringUpDeferralPolicy.DEFER_BUDGET_MS,
            )
        )
    }
}
