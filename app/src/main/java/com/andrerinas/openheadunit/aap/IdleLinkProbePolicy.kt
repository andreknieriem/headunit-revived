package com.andrerinas.openheadunit.aap

/** What to do when a socket read window closes with nothing on it. */
enum class IdleLinkAction {
    /** Ask the phone to prove it is still there, and keep reading. */
    PROBE,

    /** The peer has had its chances and answered none of them. End the session. */
    TEAR_DOWN
}

/**
 * Whether a silent read window means the link is dead, or only that nothing was said.
 *
 * A blocking read that returns empty is ambiguous. TCP cannot tell the two apart: a socket whose
 * peer has vanished reports `isConnected == true` indefinitely, because that flag records that
 * `connect()` once succeeded and nothing else. So the USB path's test — believe the read, check
 * whether the connection is still up — has no working equivalent here, and the socket path used to
 * resolve the ambiguity by assuming the worst: one empty 15 s window ended the session.
 *
 * That is the wrong half to assume. A stall of that length is ordinary on WiFi — a scan, a
 * power-save wake, a retransmission burst, or simply a head unit busy enough to not service the
 * socket for a moment — while an actually dead link is rare. Assuming the worst turns the common
 * case into a teardown, and a teardown mid-drive costs a full handshake and the "Android is
 * starting" screen. Both are recoverable; only one happens weekly.
 *
 * The way out of the ambiguity is to stop inferring and ask. The protocol already carries a ping
 * (`MESSAGE_PING_REQUEST`, which this app answers for the phone but never sends), and a phone that
 * is alive answers it. So a silent window now costs a probe rather than the session, and only a
 * peer that ignores [MAX_UNANSWERED_PROBES] consecutive probes is treated as gone.
 *
 * The counter is of *consecutive* silent windows: any inbound traffic at all — the ping response,
 * or the video frame that arrives while the probe is in flight — proves liveness and resets it.
 * That is what keeps a busy link from ever reaching the limit, and what keeps a dead one from
 * escaping it.
 */
object IdleLinkProbePolicy {

    /**
     * Consecutive unanswered probes before the link is declared dead.
     *
     * Three, against the 15 s socket read timeout, puts teardown at roughly a minute of total
     * silence. Detection stays well inside the time it takes a user to reach for the screen, and
     * the window is now wide enough that the stalls which used to end sessions no longer can.
     * Lower than three and a single stall that swallows a probe becomes a teardown again.
     */
    const val MAX_UNANSWERED_PROBES = 3

    /**
     * @param unansweredProbes probes sent since the last byte arrived from the phone. Zero on the
     *   first silent window after live traffic.
     */
    fun onSilentReadWindow(unansweredProbes: Int): IdleLinkAction =
        if (unansweredProbes < MAX_UNANSWERED_PROBES) IdleLinkAction.PROBE else IdleLinkAction.TEAR_DOWN
}
