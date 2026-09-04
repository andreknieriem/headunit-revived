package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether the credentials a handshake captured are still the ones to send.
 *
 * Type 3 is written a second after the decision to send it, and a group can be torn down inside
 * that second: measured twice on one capture, where the live group's credentials arrived 51 ms and
 * 143 ms before the write and the replaced group's name went out anyway. The phone then scans for a
 * network that no longer exists and gives up without falling back to Bluetooth.
 */
object CredentialFreshnessPolicy {

    enum class Action {
        /** Nothing changed under us. */
        SEND_AS_CAPTURED,

        /** The group was replaced; the phone wants the one that exists now. */
        SEND_LIVE,

        /** There is no network to name, so saying nothing is better than naming a dead one. */
        ABORT,
    }

    fun decide(captured: NativeNetworkCredentials, live: NativeNetworkCredentials?): Action = when {
        live == null -> Action.ABORT
        live == captured -> Action.SEND_AS_CAPTURED
        // Type 1 already told the phone where to dial. A different endpoint makes the whole
        // exchange stale, not just its last message.
        live.ip != captured.ip -> Action.ABORT
        else -> Action.SEND_LIVE
    }
}
