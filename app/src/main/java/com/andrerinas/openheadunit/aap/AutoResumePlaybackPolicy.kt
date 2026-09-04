package com.andrerinas.openheadunit.aap

/**
 * Pure decision policy for auto-resuming media playback on reconnect.
 *
 * Prevents unwanted playback if music was stopped/paused before disconnect,
 * if the feature is disabled in settings, or if the reconnect occurred after
 * a long delay (> 60 seconds).
 */
object AutoResumePlaybackPolicy {

    /** Maximum elapsed time (in ms) since disconnect to be considered a quick reconnect. */
    const val MAX_RECONNECT_WINDOW_MS = 60_000L

    /**
     * Determines whether Open Headunit should send a playback start command upon reconnection.
     *
     * @param enabled whether the user has enabled auto-resume in settings
     * @param wasPlayingBeforeDisconnect whether media was actively playing when the connection was lost
     * @param elapsedSinceDisconnectMs time in milliseconds since the disconnect occurred
     */
    fun shouldResume(
        enabled: Boolean,
        wasPlayingBeforeDisconnect: Boolean,
        elapsedSinceDisconnectMs: Long
    ): Boolean {
        if (!enabled) return false
        if (!wasPlayingBeforeDisconnect) return false
        return elapsedSinceDisconnectMs in 1..MAX_RECONNECT_WINDOW_MS
    }
}
