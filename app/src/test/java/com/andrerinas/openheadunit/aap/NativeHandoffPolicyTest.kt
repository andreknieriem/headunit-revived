package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHandoffPolicyTest {

    private val timeout = NativeHandoffPolicy.SETTLE_TIMEOUT_MS
    private val handshakeTimeout = NativeHandoffPolicy.HANDSHAKE_TIMEOUT_MS
    private val silentPokeInterval = NativeHandoffPolicy.SILENT_POKE_WARN_INTERVAL
    private val maxFailures = NativeHandoffPolicy.MAX_CONSECUTIVE_HANDSHAKE_FAILURES

    @Test
    fun `the settle cap leaves room for extensions but is not open-ended`() {
        // The phone's own progress reports (WifiConnectStatus) push the settling deadline out in
        // SETTLE_EXTENSION_MS steps. The cap has to be reachable in whole steps from the base
        // window — otherwise the last extension is silently refused at an arbitrary point — and it
        // has to be finite, so a phone that keeps saying "still joining" and never arrives cannot
        // hold Bluetooth open and the wake poke suppressed forever.
        assertTrue(NativeHandoffPolicy.MAX_SETTLE_MS > timeout)
        assertEquals(
            0L,
            (NativeHandoffPolicy.MAX_SETTLE_MS - timeout) % WppHandshakeSession.SETTLE_EXTENSION_MS
        )
    }

    @Test
    fun `no handoff is settling before any credentials go out`() {
        assertFalse(NativeHandoffPolicy.isSettling(settlingSinceMs = 0L, nowMs = 10_000L))
    }

    @Test
    fun `settles for the whole window after credentials go out`() {
        assertTrue(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L))
        assertTrue(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L + timeout / 2))
        assertTrue(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L + timeout - 1))
    }

    @Test
    fun `settling expires so a missed reset cannot latch it true forever`() {
        assertFalse(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L + timeout))
        assertFalse(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L + timeout * 10))
    }

    @Test
    fun `a clock that went backwards does not count as settling`() {
        assertFalse(NativeHandoffPolicy.isSettling(settlingSinceMs = 10_000L, nowMs = 9_000L))
    }

    @Test
    fun `no handshake is in flight before one starts`() {
        assertFalse(NativeHandoffPolicy.isHandshaking(startedAtMs = 0L, nowMs = 10_000L))
    }

    @Test
    fun `handshake counts as in flight for the whole exchange window`() {
        assertTrue(NativeHandoffPolicy.isHandshaking(startedAtMs = 1_000L, nowMs = 1_000L))
        assertTrue(
            NativeHandoffPolicy.isHandshaking(
                startedAtMs = 1_000L, nowMs = 1_000L + handshakeTimeout / 2
            )
        )
        assertTrue(
            NativeHandoffPolicy.isHandshaking(
                startedAtMs = 1_000L, nowMs = 1_000L + handshakeTimeout - 1
            )
        )
    }

    @Test
    fun `handshake expires so a coroutine that never unwinds cannot latch it true`() {
        // Where closing the socket does not unblock the pending read, handleHandshake()'s cleanup
        // never runs. Unbounded, the old boolean stayed true for the life of the process and took
        // the wake poke and the P2P join watchdog down with it.
        assertFalse(
            NativeHandoffPolicy.isHandshaking(startedAtMs = 1_000L, nowMs = 1_000L + handshakeTimeout)
        )
        assertFalse(
            NativeHandoffPolicy.isHandshaking(
                startedAtMs = 1_000L, nowMs = 1_000L + handshakeTimeout * 10
            )
        )
    }

    @Test
    fun `a clock that went backwards does not count as handshaking`() {
        assertFalse(NativeHandoffPolicy.isHandshaking(startedAtMs = 10_000L, nowMs = 9_000L))
    }

    @Test
    fun `the handshake window outlasts the longest legitimate exchange`() {
        // 60s waiting for P2P credentials plus 15s waiting for the phone's Type 2, so the bound
        // must not expire under a slow-but-working handshake.
        assertTrue(NativeHandoffPolicy.isHandshaking(startedAtMs = 1_000L, nowMs = 1_000L + 75_000L))
    }

    @Test
    fun `poke runs only when nothing else is using the radio`() {
        assertTrue(
            NativeHandoffPolicy.shouldPoke(
                settling = false, handshakeInFlight = false, sessionConnected = false
            )
        )
    }

    @Test
    fun `poke is blocked while a handoff is settling`() {
        // The phone joining the group re-delivers credentials, which re-invokes triggerPoke()
        // straight into the phone's DHCP exchange.
        assertFalse(
            NativeHandoffPolicy.shouldPoke(
                settling = true, handshakeInFlight = false, sessionConnected = false
            )
        )
    }

    @Test
    fun `poke is blocked during a handshake and once a session is up`() {
        assertFalse(
            NativeHandoffPolicy.shouldPoke(
                settling = false, handshakeInFlight = true, sessionConnected = false
            )
        )
        assertFalse(
            NativeHandoffPolicy.shouldPoke(
                settling = false, handshakeInFlight = false, sessionConnected = true
            )
        )
    }

    @Test
    fun `no warning before the phone has ignored enough pokes`() {
        assertFalse(
            NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                pokesSinceLastAccept = 0, everAccepted = false
            )
        )
        assertFalse(
            NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                pokesSinceLastAccept = silentPokeInterval - 1, everAccepted = false
            )
        )
    }

    @Test
    fun `warns once the phone has answered enough pokes without ever connecting back`() {
        assertTrue(
            NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                pokesSinceLastAccept = silentPokeInterval, everAccepted = false
            )
        )
    }

    @Test
    fun `the warning repeats so it survives into a log exported much later`() {
        assertTrue(
            NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                pokesSinceLastAccept = silentPokeInterval * 2, everAccepted = false
            )
        )
        assertFalse(
            NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                pokesSinceLastAccept = silentPokeInterval * 2 + 1, everAccepted = false
            )
        )
    }

    @Test
    fun `a unit that has connected before is never warned`() {
        assertFalse(
            NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                pokesSinceLastAccept = silentPokeInterval, everAccepted = true
            )
        )
        assertFalse(
            NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                pokesSinceLastAccept = silentPokeInterval * 10, everAccepted = true
            )
        )
    }

    @Test
    fun `handshakes are served until too many have timed out in a row`() {
        assertTrue(NativeHandoffPolicy.shouldServeHandshake(consecutiveFailures = 0))
        assertTrue(NativeHandoffPolicy.shouldServeHandshake(consecutiveFailures = maxFailures - 1))
    }

    @Test
    fun `handshakes stop being served once the limit is reached`() {
        assertFalse(NativeHandoffPolicy.shouldServeHandshake(consecutiveFailures = maxFailures))
        assertFalse(NativeHandoffPolicy.shouldServeHandshake(consecutiveFailures = maxFailures + 100))
    }

    @Test
    fun `the handshake limit leaves room for a phone that needs a few attempts`() {
        // At the phone's ~12 s reconnect cadence this is about a minute of trying, so a transient
        // failure recovers on its own rather than tripping the backoff.
        assertTrue(maxFailures >= 3)
    }

    @Test
    fun `discovery restarts when a client leaves a non-native group`() {
        assertTrue(
            NativeHandoffPolicy.shouldRestartDiscovery(
                nativeAaMode = false, hadClient = true, hasClient = false
            )
        )
    }

    @Test
    fun `discovery never restarts on the native quiet-host path`() {
        assertFalse(
            NativeHandoffPolicy.shouldRestartDiscovery(
                nativeAaMode = true, hadClient = true, hasClient = false
            )
        )
    }

    @Test
    fun `discovery does not restart unless a client actually left`() {
        assertFalse(
            NativeHandoffPolicy.shouldRestartDiscovery(
                nativeAaMode = false, hadClient = false, hasClient = false
            )
        )
        assertFalse(
            NativeHandoffPolicy.shouldRestartDiscovery(
                nativeAaMode = false, hadClient = true, hasClient = true
            )
        )
    }
}
