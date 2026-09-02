package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.direct.WifiP2pOperatingChannelPolicy

/**
 * Which 5 GHz channel the user pinned, for whichever transport is hosting the network.
 *
 * A phone's regulatory domain, not a head unit's radio, decides which 5 GHz channels it will join,
 * and a network on a channel that domain forbids is one the phone never lists at all. Several
 * domains refuse UNII-3, and Android's own group-owner selection picks a random one of the eight
 * candidates, so half the bring-ups on such a phone are invisible. This is the setting that stops
 * that being luck.
 *
 * One object rather than one per transport because the answer belongs to the user's phone and
 * country, not to WiFi Direct or the access point, and somebody who switches transport to work
 * around the problem must not silently lose the choice. What each transport can *do* with it
 * differs a great deal - see [WifiP2pOperatingChannelPolicy] and
 * [com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBandPolicy].
 */
object FiveGhzChannelPolicy {

    /** The setting's value for "let the driver choose", and the channel number that means nothing. */
    const val AUTOMATIC = WifiP2pOperatingChannelPolicy.CHANNEL_UNRESTRICTED

    /**
     * The channels offered, best first.
     *
     * UNII-1 (36-48) and the bottom of UNII-3, because those are the only two windows a group owner
     * realistically gets: everything between them is DFS, which wpa_supplicant excludes from group
     * ownership unless the driver offloads radar detection. UNII-1 is first because it is the range
     * permitted in the most domains and the one every reference head unit uses.
     */
    val CHANNELS = listOf(
        WifiP2pOperatingChannelPolicy.CHANNEL_LOWER,
        40,
        44,
        48,
        WifiP2pOperatingChannelPolicy.CHANNEL_UPPER,
    )

    /**
     * [Settings.fiveGhzChannel][com.andrerinas.openheadunit.utils.Settings.fiveGhzChannel] as a
     * channel number, or [AUTOMATIC] for automatic and for any value we do not offer.
     */
    fun pinnedChannel(setting: Int): Int = if (setting in CHANNELS) setting else AUTOMATIC

    /** The frequency a pinned channel names, or 0 for [AUTOMATIC]. */
    fun frequencyMhz(setting: Int): Int =
        WifiP2pOperatingChannelPolicy.frequencyMhzFor(pinnedChannel(setting))

    /** How the choice reads in a log line and on the settings screen. */
    fun describe(setting: Int): String {
        val channel = pinnedChannel(setting)
        if (channel == AUTOMATIC) return "automatic"
        return "channel $channel (${WifiP2pOperatingChannelPolicy.frequencyMhzFor(channel)} MHz)"
    }
}
