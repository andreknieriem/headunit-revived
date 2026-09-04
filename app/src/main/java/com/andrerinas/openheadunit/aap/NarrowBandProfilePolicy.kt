package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.utils.Settings

/**
 * What to ask the phone for when the only radio this unit has is a 2.4 GHz one.
 *
 * [com.andrerinas.openheadunit.aap.protocol.messages.ServiceDiscoveryResponse] announces one video
 * configuration, and the protocol has no bitrate field: resolution, 30-versus-60 fps and the codec
 * are the whole of what we can ask for less of.
 *
 * **This used to only advise.** Two units have now produced the same failure - the phone joins,
 * opens the video channel and closes the socket seconds later having sent no frame at all - and on
 * the second the radio has no 5 GHz band at all, so the band is not a remedy anyone can reach. A
 * lower profile held on the same access point in both cases, so the cap is now applied and
 * [Settings.narrowBandProfileCap] is how a user who disagrees turns it off.
 *
 * Pure, so the wording and every gate are a unit test rather than a device.
 */
object NarrowBandProfilePolicy {

    /** The frame rate the wire carries when the user has not asked for less. */
    const val FULL_FRAME_RATE = 60

    /** What the frame rate is lowered to. The only other value the announcement can carry. */
    const val CAPPED_FRAME_RATE = 30

    /** What the resolution is lowered to, and never below: 480p was measured, 720p is the ceiling. */
    val CAPPED_RESOLUTION = Settings.Resolution._1280x720

    /**
     * Whether this session should be asked for less than the user's settings say.
     *
     * All three gates are needed and each rules out a different false positive. A wired session
     * does not care what the radio can do. Only a `false` from the capability read means the band
     * is absent - a `true` describes the station side and a null means the platform would not
     * answer, so neither is grounds for lowering somebody's picture. And the user can say no.
     */
    fun caps(supports5Ghz: Boolean?, wirelessSession: Boolean, capEnabled: Boolean): Boolean =
        wirelessSession && supports5Ghz == false && capEnabled

    /**
     * The frame rate to announce. Only ever lowers: a user already on 30 is left there, and this
     * never raises anybody to 60.
     */
    fun cappedFrameRate(
        fpsLimit: Int,
        supports5Ghz: Boolean?,
        wirelessSession: Boolean,
        capEnabled: Boolean
    ): Int =
        if (caps(supports5Ghz, wirelessSession, capEnabled)) minOf(fpsLimit, CAPPED_FRAME_RATE)
        else fpsLimit

    /**
     * The ceiling this link puts on the resolution, or null when it puts none.
     *
     * A ceiling rather than a value, because the caller already holds one from the panel and must
     * keep taking the lower of the two. Handing back a resolution would raise a user on 480p.
     */
    fun linkCeiling(
        supports5Ghz: Boolean?,
        wirelessSession: Boolean,
        capEnabled: Boolean
    ): Settings.Resolution? =
        if (caps(supports5Ghz, wirelessSession, capEnabled)) CAPPED_RESOLUTION else null

    /**
     * One line for the log, or null when there is nothing worth saying.
     *
     * Said whenever the cap applies, including when it changed nothing, because a line that only
     * appears in the unusual case is a line whose absence tells a reader nothing.
     */
    fun advice(
        supports5Ghz: Boolean?,
        fpsLimit: Int,
        wirelessSession: Boolean,
        capEnabled: Boolean = true
    ): String? {
        if (!wirelessSession) return null
        if (supports5Ghz != false) return null
        if (!capEnabled) {
            if (fpsLimit != FULL_FRAME_RATE) return null
            return "This unit has no 5 GHz band, so this session runs over 2.4 GHz, and it is " +
                "being offered $FULL_FRAME_RATE fps because lowering the profile on a narrow band " +
                "is switched off in Video settings. Measured on a 2.4 GHz access point, a " +
                "full-rate stream died having sent no frame at all where a lower one held " +
                "indefinitely. Nothing here has been changed for you."
        }
        return "This unit has no 5 GHz band, so this session runs over 2.4 GHz. The phone is being " +
            "asked for at most ${CAPPED_RESOLUTION.resName} and $CAPPED_FRAME_RATE fps rather than " +
            "what Video settings say: measured on a 2.4 GHz access point, a full-rate stream died " +
            "having sent no frame at all where a lower one held indefinitely. Audio settings -> Use " +
            "AAC Audio takes the music from about 1.5 Mbit/s to roughly a tenth of that as well. " +
            "Turn off \"Lower video on a 2.4 GHz-only radio\" in Video settings to be given what " +
            "you asked for instead."
    }
}
