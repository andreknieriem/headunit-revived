package com.andrerinas.openheadunit.aap

/**
 * What to say when a unit with only a 2.4 GHz radio is about to ask the phone for the full frame
 * rate.
 *
 * [com.andrerinas.openheadunit.aap.protocol.messages.ServiceDiscoveryResponse] announces one video
 * configuration, and the protocol has no bitrate field: resolution, 30-versus-60 fps and the codec
 * are the whole of what we can ask for less of. So a 2.4 GHz link that cannot carry the stream has
 * exactly one cheap remedy and the app has never named it.
 *
 * **This describes and never changes anything.** The measurement behind it is real - over a 2.4 GHz
 * access point a 1080p/60 stream died having sent no frame at all, while 800x480/30 held on the same
 * access point - but the one unit where 2.4 against 5 GHz was actually run as an A/B showed no
 * difference, so the app has no grounds to overrule the user's choice. `logNegotiatedCodecCapability`
 * refuses the same move on a capability verdict for the same reason.
 *
 * Pure, so the wording and every gate are a unit test rather than a device.
 */
object NarrowBandProfilePolicy {

    /** The frame rate the wire carries when the user has not asked for less. */
    const val FULL_FRAME_RATE = 60

    /**
     * One line for the log, or null when there is nothing worth saying.
     *
     * All three gates are needed and each rules out a different false positive. A wired session does
     * not care what the radio can do. A unit that already asked for 30 has taken the advice. And
     * only a `false` from the capability read means the band is absent - a `true` describes the
     * station side and a null means the platform would not answer, so neither is grounds for
     * telling somebody their hardware is the problem.
     */
    fun advice(supports5Ghz: Boolean?, fpsLimit: Int, wirelessSession: Boolean): String? {
        if (!wirelessSession) return null
        if (supports5Ghz != false) return null
        if (fpsLimit != FULL_FRAME_RATE) return null
        return "This unit has no 5 GHz band, so this session runs over 2.4 GHz, and it is being " +
            "offered $FULL_FRAME_RATE fps. That is the most demanding thing we can ask for on the " +
            "band with the least room: measured on a 2.4 GHz access point, a full-rate stream died " +
            "having sent no frame at all where a lower one held indefinitely. If the picture stalls " +
            "or the sound breaks up, Video settings -> FPS Limit -> 30 halves what has to cross the " +
            "air, and Audio settings -> Use AAC Audio takes the music from about 1.5 Mbit/s to " +
            "roughly a tenth of that. Nothing here has been changed for you."
    }
}
