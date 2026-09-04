package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.direct.WifiP2pOperatingChannelPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiveGhzChannelPolicyTest {

    @Test
    fun `the offered channels are UNII-1 plus the bottom of UNII-3, and nothing between`() {
        // Everything between 48 and 149 is DFS, which wpa_supplicant excludes from group ownership.
        // Offering one would be offering a channel that fails the group rather than the channel.
        assertEquals(listOf(36, 40, 44, 48, 149), FiveGhzChannelPolicy.CHANNELS)
    }

    @Test
    fun `every offered channel maps to the frequency the platform derives`() {
        assertEquals(5180, FiveGhzChannelPolicy.frequencyMhz(36))
        assertEquals(5200, FiveGhzChannelPolicy.frequencyMhz(40))
        assertEquals(5220, FiveGhzChannelPolicy.frequencyMhz(44))
        assertEquals(5240, FiveGhzChannelPolicy.frequencyMhz(48))
        assertEquals(5745, FiveGhzChannelPolicy.frequencyMhz(149))
    }

    @Test
    fun `automatic is the default and has no frequency`() {
        assertEquals(FiveGhzChannelPolicy.AUTOMATIC, FiveGhzChannelPolicy.pinnedChannel(0))
        assertEquals(0, FiveGhzChannelPolicy.frequencyMhz(0))
    }

    @Test
    fun `a value we do not offer reads as automatic rather than being passed on`() {
        // A DFS channel or a stale value from a settings import must not reach the radio: a forced
        // frequency the driver refuses costs the group, so an unrecognised one has to mean nothing.
        assertEquals(FiveGhzChannelPolicy.AUTOMATIC, FiveGhzChannelPolicy.pinnedChannel(52))
        assertEquals(FiveGhzChannelPolicy.AUTOMATIC, FiveGhzChannelPolicy.pinnedChannel(165))
        assertEquals(FiveGhzChannelPolicy.AUTOMATIC, FiveGhzChannelPolicy.pinnedChannel(6))
        assertEquals(FiveGhzChannelPolicy.AUTOMATIC, FiveGhzChannelPolicy.pinnedChannel(-1))
    }

    @Test
    fun `automatic is the same value the P2P ladder means by no restriction`() {
        // The two are compared against each other in WifiDirectManager, so they must not drift.
        assertEquals(WifiP2pOperatingChannelPolicy.CHANNEL_UNRESTRICTED, FiveGhzChannelPolicy.AUTOMATIC)
    }

    @Test
    fun `every offered channel is one a group owner can actually be hosted on`() {
        // The two objects do not reference each other in that direction, so this is what keeps the
        // offered set from drifting into DFS, where a forced frequency fails the group outright.
        for (channel in FiveGhzChannelPolicy.CHANNELS) {
            assertTrue("$channel", WifiP2pOperatingChannelPolicy.isGroupOwnerCapable(channel))
        }
    }

    @Test
    fun `the description names the frequency, because that is what the log has to be read against`() {
        assertEquals("automatic", FiveGhzChannelPolicy.describe(0))
        assertTrue(FiveGhzChannelPolicy.describe(36).contains("5180"))
        assertEquals("automatic", FiveGhzChannelPolicy.describe(52))
    }
}
