package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether a `WIFI_P2P_STATE_CHANGED_ACTION` broadcast is the platform reporting something, or this
 * app hearing its own group work echoed back.
 *
 * Some drivers reload the P2P interface to create a group or apply an operating channel, which
 * raises DISABLED then ENABLED within milliseconds. Read literally that says "the user turned WiFi
 * Direct off and on", so the receiver cleared its in-flight latches and started another bring-up,
 * which reloaded the interface again: a 45 Hz loop that never let one group finish. Both rules below
 * come off a single timestamp so there is no latch to leak if a bring-up never reports an outcome.
 */
object P2pStateChangePolicy {

    /** Long enough to cover a driver reload, short enough that a real toggle still gets through. */
    const val SELF_INFLICTED_WINDOW_MS = 2_000L

    /**
     * Whether an ENABLED broadcast should start a bring-up.
     *
     * [busy] is the caller's own "a group is being created or already exists". [createClaimed] is a
     * create that has been decided on but not yet asked for: standing the station down first cycles
     * the P2P interface, so the ENABLED that follows arrives in the gap and starts a second chain
     * that removes the group the first one is about to make.
     */
    fun shouldStartBringUp(
        busy: Boolean,
        createClaimed: Boolean,
        nowMs: Long,
        lastBringUpAtMs: Long,
    ): Boolean = !busy && !createClaimed && !withinSelfInflictedWindow(nowMs, lastBringUpAtMs)

    /**
     * Whether a non-ENABLED broadcast should clear the group latches.
     *
     * False while a bring-up we just asked for could still be bouncing the interface: clearing there
     * re-opens the guard the next ENABLED reads.
     */
    fun shouldResetOnDisable(nowMs: Long, lastBringUpAtMs: Long): Boolean =
        !withinSelfInflictedWindow(nowMs, lastBringUpAtMs)

    private fun withinSelfInflictedWindow(nowMs: Long, lastBringUpAtMs: Long): Boolean =
        lastBringUpAtMs > 0L && nowMs - lastBringUpAtMs in 0 until SELF_INFLICTED_WINDOW_MS
}
