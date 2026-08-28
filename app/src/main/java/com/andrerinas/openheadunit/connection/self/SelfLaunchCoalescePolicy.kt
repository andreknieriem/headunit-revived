package com.andrerinas.openheadunit.connection.self

/**
 * Whether a Self Mode launch request should start a launch, and whether a launch that ran out of
 * launchers may say so.
 *
 * `auto-start-self-mode` and an explicit `ACTION_START_SELF_MODE` are two doors to the same room:
 * the setting is guarded inside `HomeFragment`, the intent reaches `AapService` directly, and
 * nothing downstream stopped both firing. On the AA 17.4+ route that is destructive rather than
 * merely wasteful, because there is one launcher, it dials `127.0.0.1:5277`, and the first request
 * to run out of launchers calls `emitError`, which disconnects the session the second one just
 * established.
 *
 * Hence the two rules. One launch at a time, and a launch that failed is not a failure once
 * something has connected - the same reasoning
 * [com.andrerinas.openheadunit.connection.self.SelfLaunchTimeoutPolicy] applies one step later to
 * a launch that has merely not reported yet.
 */
object SelfLaunchCoalescePolicy {

    /**
     * A second request while one is in flight is a duplicate, not a retry, and a request arriving
     * on a live session has nothing to do.
     */
    fun shouldStart(launchInFlight: Boolean, isConnected: Boolean): Boolean =
        !launchInFlight && !isConnected

    /**
     * Whether running out of launchers may be reported as the launch having failed.
     *
     * False once anything has connected: the report tears the connection down, and by then the
     * session is somebody else's, not this attempt's to end.
     */
    fun mayReportAllLaunchersFailed(isConnected: Boolean): Boolean = !isConnected
}
