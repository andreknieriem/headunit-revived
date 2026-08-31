package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.direct.P2pStateChangePolicy.SELF_INFLICTED_WINDOW_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop these rules exist to break: a bring-up reloads the P2P interface, the DISABLED that
 * follows clears the in-flight latch, and the ENABLED after it starts another bring-up. Measured at
 * ~45 iterations a second on a Qualcomm sm6150 unit, with no group ever formed.
 */
class P2pStateChangePolicyTest {

    private val now = 1_000_000L

    @Test
    fun `a disable moments after our own bring-up is ours, and clears nothing`() {
        assertFalse(P2pStateChangePolicy.shouldResetOnDisable(now, lastBringUpAtMs = now - 20))
    }

    @Test
    fun `a disable long after the last bring-up is the user, and clears the latches`() {
        assertTrue(P2pStateChangePolicy.shouldResetOnDisable(now, lastBringUpAtMs = now - 60_000))
    }

    @Test
    fun `never having brought a group up cannot make a disable ours`() {
        assertTrue(P2pStateChangePolicy.shouldResetOnDisable(now, lastBringUpAtMs = 0L))
    }

    @Test
    fun `the window ends, so a driver that stays quiet does not latch the latches shut`() {
        assertFalse(P2pStateChangePolicy.shouldResetOnDisable(now, now - SELF_INFLICTED_WINDOW_MS + 1))
        assertTrue(P2pStateChangePolicy.shouldResetOnDisable(now, now - SELF_INFLICTED_WINDOW_MS))
    }

    @Test
    fun `the echo of our own bring-up does not start another one`() {
        assertFalse(P2pStateChangePolicy.shouldStartBringUp(busy = false, nowMs = now, lastBringUpAtMs = now - 20))
    }

    @Test
    fun `a group already up or being created is reason enough not to start`() {
        assertFalse(P2pStateChangePolicy.shouldStartBringUp(busy = true, nowMs = now, lastBringUpAtMs = 0L))
    }

    @Test
    fun `an idle unit whose P2P has just come up does start`() {
        assertTrue(P2pStateChangePolicy.shouldStartBringUp(busy = false, nowMs = now, lastBringUpAtMs = 0L))
        assertTrue(P2pStateChangePolicy.shouldStartBringUp(busy = false, nowMs = now, lastBringUpAtMs = now - 60_000))
    }

    @Test
    fun `a clock that went backwards is not read as a self-inflicted bounce`() {
        assertTrue(P2pStateChangePolicy.shouldResetOnDisable(now, lastBringUpAtMs = now + 5_000))
        assertTrue(P2pStateChangePolicy.shouldStartBringUp(busy = false, nowMs = now, lastBringUpAtMs = now + 5_000))
    }
}
