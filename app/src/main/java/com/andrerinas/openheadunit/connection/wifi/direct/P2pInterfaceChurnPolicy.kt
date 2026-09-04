package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether this unit's WiFi Direct is being switched off and on by something other than this app.
 *
 * A driver reloads the P2P interface to create a group, so a burst of DISABLED/ENABLED on its own
 * says nothing about who caused it. Only edges arriving while no request of ours is outstanding
 * count here, which is the one measurement that separates the two. A stack somebody else is cycling
 * refuses every `createGroup` with BUSY, so naming it is the difference between an unreadable log
 * and an answer.
 *
 * It follows that this stays quiet whenever our own retry cadence leaves no gap to measure in, and
 * on three of the five captures behind it that is exactly what happened. Silence here is "not
 * shown", never "not happening": accusing another app on a log that cannot tell the two apart is
 * the one error worth designing against, because the remedy it names is the user disabling
 * something.
 */
object P2pInterfaceChurnPolicy {

    /** Long enough that our own removeGroup/createGroup/setOperatingChannel round trip has ended. */
    const val QUIET_AFTER_OUR_REQUEST_MS = 3_000L

    /** Short enough to answer within one bring-up, long enough to average out a driver reload. */
    const val WINDOW_MS = 5_000L

    /**
     * A driver reload for one group is a handful of edges per window.
     *
     * Measured on the unit that prompted this: ~450 per window, sustained, with nothing of ours
     * outstanding. The gap between the two is wide enough that this can sit an order of magnitude
     * above anything legitimate and still fire on the real case.
     */
    const val FOREIGN_CHURN_PER_WINDOW = 40

    /** One line a minute. The condition is continuous; saying so more often buys nothing. */
    const val REPORT_INTERVAL_MS = 60_000L

    /** Per window, so a real toggle still shows up and a cycled stack is not 23,000 log lines. */
    const val LOG_QUOTA_PER_WINDOW = 4

    /**
     * Whether an edge arriving now is attributable to something other than us.
     *
     * @param lastP2pRequestAtMs when we last asked the platform for anything, 0 for never.
     */
    fun countsAsForeign(nowMs: Long, lastP2pRequestAtMs: Long): Boolean {
        if (lastP2pRequestAtMs <= 0L) return true
        val since = nowMs - lastP2pRequestAtMs
        // A stamp in the future is a clock that moved under us; treat it as ours and stay quiet.
        if (since < 0L) return false
        return since >= QUIET_AFTER_OUR_REQUEST_MS
    }

    /** Whether the window that just ended was somebody else cycling the interface. */
    fun isForeignChurn(foreignEventsInWindow: Int): Boolean =
        foreignEventsInWindow >= FOREIGN_CHURN_PER_WINDOW

    /** Whether this broadcast still earns its own log line. */
    fun shouldLogEachBroadcast(eventsInWindow: Int): Boolean =
        eventsInWindow <= LOG_QUOTA_PER_WINDOW

    /** Whether the churn is worth saying again. */
    fun shouldReport(nowMs: Long, lastReportAtMs: Long): Boolean {
        if (lastReportAtMs <= 0L) return true
        val since = nowMs - lastReportAtMs
        if (since < 0L) return true
        return since >= REPORT_INTERVAL_MS
    }
}
