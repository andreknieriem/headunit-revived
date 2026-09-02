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

    fun decide(groupExists: Boolean, isGroupOwner: Boolean, createInFlightForMs: Long?): Action = when {
        groupExists && isGroupOwner -> Action.REDELIVER
        createInFlightForMs != null && createInFlightForMs in 0 until CREATE_GRACE_MS -> Action.WAIT
        else -> Action.RECREATE
    }
}
