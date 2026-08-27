package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frequencies are the ones the platform derives, not ones chosen here: `SupplicantP2pIfaceHal`
 * turns an operating channel into `(channel <= 14 ? 2407 : 5000) + channel * 5` and disallows
 * everything either side of it. If these numbers are wrong the driver is asked for the wrong band.
 */
class WifiP2pOperatingChannelPolicyTest {

    private val api27 = 27
    private val api29 = 29
    private val api34 = 34

    @Test
    fun `channel 36 is 5180 MHz, which is what makes this worth doing`() {
        assertEquals(5180, WifiP2pOperatingChannelPolicy.frequencyMhzFor(36))
    }

    @Test
    fun `the upper band channel is 5745 MHz`() {
        assertEquals(5745, WifiP2pOperatingChannelPolicy.frequencyMhzFor(149))
    }

    @Test
    fun `the 2_4 GHz channels use the other base, so a mix-up would be visible`() {
        assertEquals(2412, WifiP2pOperatingChannelPolicy.frequencyMhzFor(1))
        assertEquals(2437, WifiP2pOperatingChannelPolicy.frequencyMhzFor(6))
        assertEquals(2462, WifiP2pOperatingChannelPolicy.frequencyMhzFor(11))
        // 13 then 14 pins the discontinuity: the linear formula holds up to 13 and then stops, so a
        // converter that lost the special case would answer 2477 here and pass every other case.
        assertEquals(2472, WifiP2pOperatingChannelPolicy.frequencyMhzFor(13))
        assertEquals(2484, WifiP2pOperatingChannelPolicy.frequencyMhzFor(14))
    }

    @Test
    fun `the 2_4 GHz conversion agrees with the one that reads a group's frequency back`() {
        // WifiP2pChannelPolicy converts frequency to channel and this converts channel to frequency, so
        // a round trip has to close. It did not: this policy was written with a flat 5 MHz step and
        // answered 2477 for channel 14, which the other object would have read back as no channel
        // at all. Any future divergence between the two shows up here first.
        for (channel in 1..14) {
            assertEquals(
                channel,
                WifiP2pChannelPolicy.channelFor(WifiP2pOperatingChannelPolicy.frequencyMhzFor(channel)),
            )
        }
    }

    @Test
    fun `a channel the platform would reject has no frequency`() {
        assertEquals(0, WifiP2pOperatingChannelPolicy.frequencyMhzFor(0))
        assertEquals(0, WifiP2pOperatingChannelPolicy.frequencyMhzFor(166))
        assertEquals(0, WifiP2pOperatingChannelPolicy.frequencyMhzFor(-1))
    }

    @Test
    fun `this applies only below the API that has a band request`() {
        assertTrue(WifiP2pOperatingChannelPolicy.appliesTo(21))
        assertTrue(WifiP2pOperatingChannelPolicy.appliesTo(api27))
        assertTrue(WifiP2pOperatingChannelPolicy.appliesTo(28))
        assertFalse(WifiP2pOperatingChannelPolicy.appliesTo(api29))
        assertFalse(WifiP2pOperatingChannelPolicy.appliesTo(api34))
    }

    @Test
    fun `a modern device is never given a channel, because it has the supported band request`() {
        for (preference in P2pBandPreference.values()) {
            assertEquals("$preference", emptyList<Int>(), WifiP2pOperatingChannelPolicy.attemptChannels(api29, preference))
            assertEquals(
                "$preference",
                emptyList<Int>(),
                WifiP2pOperatingChannelPolicy.attemptChannels(api34, preference, useUpperBand = true)
            )
        }
    }

    @Test
    fun `an old device tries 5 GHz and then 2_4 GHz, so a unit that cannot host one still gets a group`() {
        // The request is a disallowed-frequency list, so naming a band a unit cannot host used to
        // leave it with nowhere legal to put a group rather than on the other band. Both rungs are
        // offered before the caller gives the restriction back entirely.
        assertEquals(listOf(36, 6), WifiP2pOperatingChannelPolicy.attemptChannels(api27, P2pBandPreference.AUTO))
    }

    @Test
    fun `5 GHz only never names a 2_4 GHz channel, on either range`() {
        assertEquals(listOf(36), WifiP2pOperatingChannelPolicy.attemptChannels(api27, P2pBandPreference.FORCE_5GHZ))
        assertEquals(
            listOf(149),
            WifiP2pOperatingChannelPolicy.attemptChannels(api27, P2pBandPreference.FORCE_5GHZ, useUpperBand = true)
        )
        for (useUpper in listOf(false, true)) {
            for (channel in WifiP2pOperatingChannelPolicy.attemptChannels(api27, P2pBandPreference.FORCE_5GHZ, useUpper)) {
                assertTrue(
                    "channel $channel is not 5 GHz",
                    WifiP2pOperatingChannelPolicy.frequencyMhzFor(channel) > 5000
                )
            }
        }
    }

    @Test
    fun `2_4 GHz only asks for one channel and never a 5 GHz one`() {
        assertEquals(listOf(6), WifiP2pOperatingChannelPolicy.attemptChannels(api27, P2pBandPreference.FORCE_2_4GHZ))
        assertEquals(
            "the upper-band flag belongs to the 5 GHz rung, which this preference never reaches",
            listOf(6),
            WifiP2pOperatingChannelPolicy.attemptChannels(api27, P2pBandPreference.FORCE_2_4GHZ, useUpperBand = true)
        )
    }

    @Test
    fun `every rung is a channel the platform will accept`() {
        for (preference in P2pBandPreference.values()) {
            for (useUpper in listOf(false, true)) {
                for (channel in WifiP2pOperatingChannelPolicy.attemptChannels(api27, preference, useUpper)) {
                    assertTrue("$preference/$channel", WifiP2pOperatingChannelPolicy.isRequestable(channel))
                    assertTrue(
                        "a rung must never be the sentinel that means 'ask for nothing'",
                        channel != WifiP2pOperatingChannelPolicy.CHANNEL_UNRESTRICTED
                    )
                }
            }
        }
    }

    @Test
    fun `the upper band is only reached when it is asked for`() {
        assertEquals(
            listOf(149, 6),
            WifiP2pOperatingChannelPolicy.attemptChannels(api27, P2pBandPreference.AUTO, useUpperBand = true),
        )
        assertEquals(
            "the flag must not smuggle UNII-3 onto a preference that never asks for 5 GHz",
            listOf(6),
            WifiP2pOperatingChannelPolicy.attemptChannels(api27, P2pBandPreference.FORCE_2_4GHZ, useUpperBand = true),
        )
    }

    @Test
    fun `every channel this policy can return is one the platform accepts`() {
        assertTrue(WifiP2pOperatingChannelPolicy.isRequestable(WifiP2pOperatingChannelPolicy.CHANNEL_LOWER))
        assertTrue(WifiP2pOperatingChannelPolicy.isRequestable(WifiP2pOperatingChannelPolicy.CHANNEL_UPPER))
        assertTrue(WifiP2pOperatingChannelPolicy.isRequestable(WifiP2pOperatingChannelPolicy.CHANNEL_UNRESTRICTED))
        assertFalse(WifiP2pOperatingChannelPolicy.isRequestable(166))
        assertFalse(WifiP2pOperatingChannelPolicy.isRequestable(-1))
    }

    @Test
    fun `both offered channels are outside the DFS range a group owner may not use`() {
        // wpa_supplicant marks operating classes 118-123 (channels 52-140) NO_P2P_SUPP, so a group
        // owner asked for one of those cannot start at all. Both channels here sit outside it.
        assertTrue(WifiP2pOperatingChannelPolicy.CHANNEL_LOWER < 52)
        assertTrue(WifiP2pOperatingChannelPolicy.CHANNEL_UPPER > 140)
    }
}
