package com.andrerinas.openheadunit.connection.wifi.direct


/**
 * Which operating channel to ask the P2P stack for, on the devices that have no band API.
 *
 * [com.andrerinas.openheadunit.connection.wifi.direct.NativeGroupBandPolicy] asks for a *band*, and that request only exists from API 29
 * (`WifiP2pConfig.Builder.setGroupOperatingBand`). Below it the app calls the no-argument
 * `createGroup` and the driver picks unaided, which is what every pre-Android-10 head unit in the
 * reports has been doing: `Standard createGroup SUCCESS!`, then `Freq: 0 MHz (unknown)`, with the
 * band unrecorded on our side and unchosen on theirs.
 *
 * There is one lever left on those devices. `WifiP2pManager.setWifiP2pChannels(c, listen, operating,
 * listener)` is hidden but present, and in AOSP 8.1's `SupplicantP2pIfaceHal` an operating channel is
 * turned into `setDisallowedFrequencies` around the single frequency it names - channel 36 becomes
 * 5180 MHz and everything either side of it is disallowed, so the group owner has nowhere else to go.
 * Reflection reaches it because the non-SDK blocklist only starts at API 28, and this app already
 * reflects into the same class for `setDeviceName`.
 *
 * Two constraints come from the platform and both are encoded here rather than left to the caller:
 *
 *  - The channel must be set **while no group exists**. `WifiP2pServiceImpl` handles `SET_CHANNEL`
 *    only in its inactive state, so a request made after `createGroup` is dropped silently.
 *  - The **listen** channel must be left alone. Discovery happens on the 2.4 GHz social channels
 *    (1, 6, 11) whatever the group runs on, and restricting it would make the unit undiscoverable.
 *    [LISTEN_CHANNEL_UNCHANGED] is the value that means "do not touch it".
 *
 * The request is tried as a ladder rather than once - see [attemptChannels] - because a frequency
 * list a unit cannot satisfy costs it the group rather than the band.
 *
 * Channel 36 rather than any other: it is the bottom of UNII-1, it is not a DFS channel, and it is
 * what the reference implementations use - `WirelessAndroidAutoDongle` brings its access point up on
 * channel 36, and the AAWireless dongle's own filing lists 5180-5240 and 5745-5825 while avoiding the
 * DFS range between them. [CHANNEL_UPPER] is offered for the same reason the filing lists two ranges:
 * a unit whose regulatory domain refuses UNII-1 may still take UNII-3.
 */
object WifiP2pOperatingChannelPolicy {

    /** Ask for nothing and leave the platform's own choice alone. */
    const val CHANNEL_UNRESTRICTED = 0

    /** Pass this as the listen channel: discovery must stay where the phone looks for it. */
    const val LISTEN_CHANNEL_UNCHANGED = 0

    /** 5180 MHz, bottom of UNII-1. Not a DFS channel. */
    const val CHANNEL_LOWER = 36

    /** 5745 MHz, bottom of UNII-3, for a regulatory domain that refuses UNII-1. */
    const val CHANNEL_UPPER = 149

    /**
     * 2437 MHz, the middle of the three non-overlapping 2.4 GHz channels (1, 6 and 11).
     *
     * The rung the ladder falls to when 5 GHz will not host a group owner. The middle one rather
     * than an edge because nothing here knows what else is on the air, and 6 is the conventional
     * default when there is nothing to choose on.
     */
    const val CHANNEL_24_GHZ = 6

    /** The last 2.4 GHz channel, and the one the linear formula does not describe. */
    const val CHANNEL_24_GHZ_TOP = 14

    /** The band request exists from here up, so below it is where this policy applies. */
    const val FIRST_API_WITH_BAND_REQUEST = 29

    /**
     * True when asking for an operating channel is the only way to influence the band.
     *
     * From API 29 the band request is a supported call with a supported fallback, and it is what
     * [com.andrerinas.openheadunit.connection.wifi.direct.NativeGroupBandPolicy] already drives; reaching for a hidden method there would be trading a
     * guarantee for a reflection.
     */
    fun appliesTo(sdkInt: Int): Boolean = sdkInt < FIRST_API_WITH_BAND_REQUEST

    /**
     * The operating channels to ask for, best first, or empty where asking is not the lever.
     *
     * A ladder rather than a single answer, because the request is a *disallowed-frequency list*
     * and that is the whole hazard: a unit whose P2P firmware cannot host a group owner on the band
     * it names is not left on the other one, it is left with nowhere legal to put a group and fails
     * outright. Naming 2.4 GHz explicitly on the way down gives it somewhere to land, and the caller
     * clears the restriction entirely once this list is spent - so the worst case is the behaviour
     * that shipped before any of this existed, reached one bring-up later.
     *
     * That ladder is also what makes 5 GHz safe to try by default here. It used to be an opt-in
     * precisely because a wrong answer cost a pre-Q unit its connection rather than its band; with
     * somewhere to fall back to, a wrong answer costs a round trip.
     *
     * @param sdkInt this device's API level. Empty from [FIRST_API_WITH_BAND_REQUEST] up, where the
     *   supported band request does this properly and reaching for a hidden method would be trading
     *   a guarantee for a reflection.
     * @param preference the user's band choice.
     * @param chosenChannel the channel the user pinned, or [CHANNEL_UNRESTRICTED] for automatic,
     *   which asks for [CHANNEL_LOWER] alone. A pinned channel is walked across its own UNII window
     *   instead - see [fiveGhzWalk] - because somebody who named a channel has said the driver's own
     *   pick does not work for them, and one refusal is not the window's answer. The 2.4 GHz rung
     *   below it stays either way, because a disallowed-frequency list a unit cannot satisfy costs
     *   it the group rather than the band.
     * @param supports5Ghz [WifiBandCapability.supports5Ghz], where null means the platform would not
     *   say. Only `false` drops a rung, and only under [P2pBandPreference.AUTO]: a `true` describes
     *   the station side and does not promise a group owner can be hosted there, so the ladder keeps
     *   its own fallback rather than trusting it. A user who asked for 5 GHz still gets the request
     *   made - the setting exists for a driver that surprises this call.
     */
    fun attemptChannels(
        sdkInt: Int,
        preference: P2pBandPreference,
        chosenChannel: Int = CHANNEL_UNRESTRICTED,
        supports5Ghz: Boolean? = null
    ): List<Int> {
        if (!appliesTo(sdkInt)) return emptyList()
        // Total rather than trusting the caller: anything a group owner cannot be hosted on falls
        // back to the rung that shipped before the choice existed.
        val fiveGhz =
            if (isGroupOwnerCapable(chosenChannel)) fiveGhzWalk(chosenChannel) else listOf(CHANNEL_LOWER)
        return when (preference) {
            // Spending the 5 GHz rung on a radio with no 5 GHz band costs a whole bring-up: the
            // request is a disallowed-frequency list, so the group is not formed on the other band,
            // it is not formed at all, and the ladder only advances on that failure.
            P2pBandPreference.AUTO ->
                if (supports5Ghz == false) listOf(CHANNEL_24_GHZ) else fiveGhz + CHANNEL_24_GHZ
            P2pBandPreference.FORCE_5GHZ -> fiveGhz
            P2pBandPreference.FORCE_2_4GHZ -> listOf(CHANNEL_24_GHZ)
        }
    }

    /**
     * The pinned channel, then the rest of its own UNII window.
     *
     * A refusal is the driver saying it will not host a group owner on that frequency, and it says
     * nothing about the three beside it, so one channel must not condemn the window the user chose.
     * Only that window: somebody who pinned UNII-1 is escaping UNII-3, and walking into it would
     * land them where they started.
     */
    private fun fiveGhzWalk(chosenChannel: Int): List<Int> {
        val window = if (chosenChannel in UNII_1_CHANNELS) UNII_1_CHANNELS else UNII_3_CHANNELS
        return listOf(chosenChannel) + window.filter { it != chosenChannel }
    }

    /**
     * Whether a spent ladder means this unit will not host a group owner on 5 GHz at all.
     *
     * True only once every 5 GHz rung the user's pin produced has been refused, which is the one
     * finding worth telling them about: the channel setting cannot be honoured here and the band is
     * the lever left. An automatic pin never asks, because the driver's own choice is not a refusal.
     *
     * @param ladder [attemptChannels]' answer for this bring-up.
     * @param rungsSpent how many rungs have already been tried, so equal to the ladder's 5 GHz count
     *   means all of them were.
     * @param pinnedChannel the *sanitized* pin
     *   ([FiveGhzChannelPolicy.pinnedChannel][com.andrerinas.openheadunit.connection.wifi.FiveGhzChannelPolicy.pinnedChannel]),
     *   because an unrecognised stored value runs the default ladder and must not be blamed on a
     *   channel the user could never have chosen.
     */
    fun refusedEveryFiveGhzRung(ladder: List<Int>, rungsSpent: Int, pinnedChannel: Int): Boolean {
        if (pinnedChannel == CHANNEL_UNRESTRICTED) return false
        val fiveGhzRungs = ladder.count { frequencyMhzFor(it) > 5000 }
        return fiveGhzRungs > 0 && rungsSpent >= fiveGhzRungs
    }

    /**
     * The frequency a channel number names: 2.4 GHz channels count from 2407 MHz, 5 GHz channels from
     * 5000 MHz, and channel 14 is the standard's own exception to both.
     *
     * Here so a test can assert what the app is actually asking the driver for, and so the log can
     * name the frequency rather than a channel number the reader has to convert.
     */
    fun frequencyMhzFor(channel: Int): Int = when {
        channel !in 1..165 -> 0
        // Channel 14 sits 12 MHz above 13 rather than 5, so the arithmetic does not reach it and
        // AOSP special-cases it in ScanResult.convertChannelToFrequencyMhzIfSupported for the same
        // reason. It is Japan-only and 802.11b-only, so attemptChannels() will never name it -
        // but a converter that quietly answers 2477 is worse than one that refuses. The constant is
        // P2pChannelPolicy's because that object converts the other way and must agree with this one.
        channel == CHANNEL_24_GHZ_TOP -> WifiP2pChannelPolicy.CHANNEL_14_MHZ
        channel <= CHANNEL_24_GHZ_TOP -> 2407 + channel * 5
        else -> 5000 + channel * 5
    }

    /** A channel the platform will accept. Outside this it rejects the whole request. */
    fun isRequestable(channel: Int): Boolean = channel == CHANNEL_UNRESTRICTED || channel in 1..165

    /**
     * True for a 5 GHz channel a group owner can realistically be hosted on.
     *
     * Operating class 115 (UNII-1) and 124/125 (UNII-3), which is the whole of it: everything
     * between them is DFS, and wpa_supplicant excludes DFS from group ownership unless the driver
     * offloads radar detection, so a forced frequency there fails the group rather than the
     * channel. `isRequestable` is a wider question - what the *call* will take - and answering yes
     * to 52 is correct there and wrong here.
     */
    fun isGroupOwnerCapable(channel: Int): Boolean =
        channel in UNII_1_CHANNELS || channel in UNII_3_CHANNELS

    private val UNII_1_CHANNELS = listOf(36, 40, 44, 48)
    // 165 is absent on purpose. createGroup asks wpa_supplicant to pick with freq=0, and
    // wpas_p2p_select_go_freq_no_pref proposes 5180/5200/5220/5240 then 5745/5765/5785/5805 and
    // nothing else, so 5825 cannot be reached through this API however the radio is configured.
    private val UNII_3_CHANNELS = listOf(149, 153, 157, 161)
}
