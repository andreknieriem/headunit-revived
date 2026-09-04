package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SessionEndGroupPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEndGroupPolicyTest {

    @Test
    fun `a user exit stops the launcher so the phone leaves the network`() {
        assertEquals(
            Action.STOP,
            SessionEndGroupPolicy.decide(activeModeIsNative = true, isUserExit = true, rearmedAfterWiredSession = false)
        )
    }

    /**
     * The point of the policy: a session that ends on its own leaves the network up. Removing
     * it and creating another gives the group a new address on units that re-address, and the
     * phone then spends seconds on the network it saved before it takes the new one.
     */
    @Test
    fun `an unexpected end keeps the network up for the phone's return`() {
        assertEquals(
            Action.KEEP_AND_REARM,
            SessionEndGroupPolicy.decide(activeModeIsNative = true, isUserExit = false, rearmedAfterWiredSession = false)
        )
    }

    @Test
    fun `a mode that is not Native is not this policy's session`() {
        for (mode in WifiLauncherMode.entries) {
            if (mode == WifiLauncherMode.NATIVE) continue
            for (userExit in listOf(true, false)) {
                assertEquals(
                    "mode=$mode userExit=$userExit",
                    Action.NONE,
                    SessionEndGroupPolicy.decide(activeModeIsNative = false, isUserExit = userExit, rearmedAfterWiredSession = false)
                )
            }
        }
    }

    @Test
    fun `a wired session's re-arm owns the wireless stack`() {
        assertEquals(
            Action.NONE,
            SessionEndGroupPolicy.decide(activeModeIsNative = true, isUserExit = true, rearmedAfterWiredSession = true)
        )
        assertEquals(
            Action.NONE,
            SessionEndGroupPolicy.decide(activeModeIsNative = true, isUserExit = false, rearmedAfterWiredSession = true)
        )
    }

    @Test
    fun `listeners closed by a completed handoff are reopened`() {
        assertTrue(SessionEndGroupPolicy.shouldReopenAaListeners(handshakeRunning = true, listenersClosedForSession = true))
    }

    /** A session that landed on the TCP port without a handshake never closed them. */
    @Test
    fun `listeners that were never closed are not opened twice`() {
        assertFalse(SessionEndGroupPolicy.shouldReopenAaListeners(handshakeRunning = true, listenersClosedForSession = false))
    }

    @Test
    fun `a manager that never started is not the re-arm's to open`() {
        assertFalse(SessionEndGroupPolicy.shouldReopenAaListeners(handshakeRunning = false, listenersClosedForSession = true))
        assertFalse(SessionEndGroupPolicy.shouldReopenAaListeners(handshakeRunning = false, listenersClosedForSession = false))
    }
}
