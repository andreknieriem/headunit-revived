package com.andrerinas.openheadunit.aap

/**
 * Pacing rule for [AapVideo]'s stream recovery.
 *
 * Recovering from a corrupt frame means asking the phone for a fresh keyframe, which
 * [AapTransport] implements as a video-focus loss/regain cycle. That is expensive and briefly
 * visible on screen, and the phone reacts to it by rebuilding the stream, so a burst of requests
 * costs far more picture than the single frame that triggered the first one. Rate limit them.
 *
 * Suppressing a request is safe to do silently: a corrupt frame is scoped to itself and the stream
 * resumes on the next one either way, so a suppressed request only means recovery waits for the
 * phone's own keyframe cadence rather than being brought forward.
 */
object VideoRecoveryPolicy {

    /** Minimum spacing between keyframe requests, in milliseconds. */
    const val KEYFRAME_REQUEST_THROTTLE_MS = 1000L

    /** Whether enough time has passed since [lastRequestMs] to send another keyframe request. */
    fun canRequestKeyframe(nowMs: Long, lastRequestMs: Long): Boolean =
        nowMs - lastRequestMs > KEYFRAME_REQUEST_THROTTLE_MS
}
