package com.andrerinas.openheadunit.app

/** What an ACL event from a Bluetooth device does to a pending auto-disconnect. */
enum class BtAutoDisconnectArm { IGNORE, ARM, CANCEL }

/**
 * Ends a session when a chosen device's Bluetooth link goes away, the way the Exit button does.
 * The loss arms a timer that the device coming back cancels, and nothing fires without a session:
 * Native AA's wake poke opens and closes a socket to the phone on every cycle.
 */
object BtAutoDisconnectPolicy {

    /**
     * How long a session must have run before a Bluetooth loss may end it. The handshake socket
     * closes seconds after the handoff and looks exactly like the phone leaving.
     */
    const val MIN_SESSION_AGE_MS = 60_000L

    const val MAX_DELAY_SECONDS = 3600

    fun onAclEvent(watchedMacs: Set<String>, deviceMac: String?, connected: Boolean): BtAutoDisconnectArm {
        if (deviceMac == null || deviceMac !in watchedMacs) return BtAutoDisconnectArm.IGNORE
        return if (connected) BtAutoDisconnectArm.CANCEL else BtAutoDisconnectArm.ARM
    }

    fun graceDelayMs(delaySeconds: Int): Long = delaySeconds.coerceIn(0, MAX_DELAY_SECONDS) * 1000L

    /** Asked once the grace delay has run out. */
    fun shouldEndSession(sessionUp: Boolean, sessionAgeMs: Long, deviceCameBack: Boolean): Boolean =
        sessionUp && !deviceCameBack && sessionAgeMs >= MIN_SESSION_AGE_MS
}
