package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * What a credential refresh does to the Native AA group.
 *
 * The handshake asks for one every ten seconds while it waits for credentials, and before every
 * poke. It used to be a full teardown and recreate, which handed the phone a new network name in
 * the very window it was trying to join one. A group that is up and ours only needs its credentials
 * read again; one that was just asked for needs to be left to answer; only no group at all is
 * worth creating one.
 */
object NativeRefreshPolicy {

    /** How long a createGroup is given to answer before a refresh stops waiting on it. */
    const val CREATE_GRACE_MS = 15_000L

    enum class Action { REDELIVER, WAIT, RECREATE }

    /** Whether a create asked for this long ago is still owed an answer. */
    fun withinCreateGrace(elapsedMs: Long?): Boolean =
        elapsedMs != null && elapsedMs in 0 until CREATE_GRACE_MS

    /**
     * The same question read off the stamp, for callers that have to decide before the group can
     * answer. A create is several async hops from the call that asked for it, and anything that
     * tears the mode down in between starts a second one underneath the first.
     */
    fun createInFlight(requestedAtMs: Long, nowMs: Long): Boolean =
        requestedAtMs != 0L && withinCreateGrace(nowMs - requestedAtMs)

    fun decide(groupExists: Boolean, isGroupOwner: Boolean, createInFlightForMs: Long?): Action = when {
        groupExists && isGroupOwner -> Action.REDELIVER
        withinCreateGrace(createInFlightForMs) -> Action.WAIT
        else -> Action.RECREATE
    }
}
