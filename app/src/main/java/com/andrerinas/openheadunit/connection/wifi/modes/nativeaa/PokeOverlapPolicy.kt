package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether a wake poke may open a socket while another one is already connecting to the same phone.
 *
 * `pokeJob.cancel()` cannot interrupt a blocking `socket.connect()`, so a manual poke that replaces
 * the automatic one still runs beside it. Measured on a POCO/Moto pair: two connects 235 ms apart
 * left all four RFCOMM attempts failing with `read ret: -1`, and the attempt 42 s later worked
 * first try.
 *
 * Pure and unit-tested; the sockets live in `NativeAaHandshakeManager`.
 */
object PokeOverlapPolicy {

    /** How long to wait for an in-flight connect. The observed HFP-AG refusal takes ~3.05 s. */
    const val CONNECT_SETTLE_WAIT_MS = 4_000L

    /** Poll interval while waiting. */
    const val POLL_MS = 100L

    enum class Step {
        /** Another poke is inside connect() for this phone; give it a moment. */
        WAIT,

        /** Nothing in the way, or the wait is spent. Open the socket. */
        PROCEED
    }

    /**
     * [connectingTo] is the address a poke is inside `socket.connect()` for, or null when none is.
     * Only the same phone is worth waiting for: a poke aimed elsewhere shares no RFCOMM channel.
     */
    fun step(connectingTo: String?, target: String, waitedMs: Long): Step =
        if (waitedMs < CONNECT_SETTLE_WAIT_MS &&
            connectingTo != null &&
            target.isNotEmpty() &&
            connectingTo.equals(target, ignoreCase = true)
        ) Step.WAIT else Step.PROCEED
}
