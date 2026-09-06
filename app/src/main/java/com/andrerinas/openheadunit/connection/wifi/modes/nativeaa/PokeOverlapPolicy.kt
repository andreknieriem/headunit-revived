package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether a wake poke may open a socket while another one is already connecting to the same phone.
 *
 * `pokeJob.cancel()` cannot interrupt a blocking `socket.connect()`, so a poke that replaced another
 * still runs beside it. Measured on a POCO/Moto pair: two connects 235 ms apart left all four RFCOMM
 * attempts failing with `read ret: -1`, and the attempt 42 s later worked first try.
 *
 * Pure and unit-tested; the sockets live in `NativeAaHandshakeManager`.
 */
object PokeOverlapPolicy {

    /** How long to wait for an in-flight connect. A phone with Bluetooth off takes 15.4 s to fail. */
    const val CONNECT_SETTLE_WAIT_MS = 20_000L

    /** Poll interval while waiting. */
    const val POLL_MS = 100L

    enum class Step {
        /** Another poke is inside connect() for this phone; give it a moment. */
        WAIT,

        /** It is still there after the whole wait. Do not add a second socket to a stuck one. */
        ABANDON,

        /** Nothing in the way. Open the socket. */
        PROCEED
    }

    /**
     * [connectingTo] is the address a poke is inside `socket.connect()` for, or null when none is.
     * Only the same phone is worth waiting for: a poke aimed elsewhere shares no RFCOMM channel.
     */
    fun step(connectingTo: String?, target: String, waitedMs: Long): Step =
        if (connectingTo != null &&
            target.isNotEmpty() &&
            connectingTo.equals(target, ignoreCase = true)
        ) {
            if (waitedMs < CONNECT_SETTLE_WAIT_MS) Step.WAIT else Step.ABANDON
        } else Step.PROCEED
}
