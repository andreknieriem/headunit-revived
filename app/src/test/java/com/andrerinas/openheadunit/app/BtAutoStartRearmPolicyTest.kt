package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtAutoStartRearmPolicyTest {

    private fun actions(
        mode: WifiLauncherMode = WifiLauncherMode.NATIVE,
        wirelessSelected: Boolean = true,
        sessionUp: Boolean = false,
        wirelessArmed: Boolean = false,
        handshakeActive: Boolean? = null,
        attemptInFlight: Boolean? = null,
        groupUp: Boolean? = null,
        networkComingUp: Boolean? = false
    ) = BtAutoStartRearmPolicy.actionsFor(
        mode = mode,
        wirelessSelected = wirelessSelected,
        sessionUp = sessionUp,
        wirelessArmed = wirelessArmed,
        handshakeActive = handshakeActive,
        attemptInFlight = attemptInFlight,
        groupUp = groupUp,
        networkComingUp = networkComingUp
    )

    private val otherWirelessModes = WifiLauncherMode.entries.filter { it != WifiLauncherMode.NATIVE }

    /**
     * The state this policy exists for: a Native user exit nulled the launcher and the phone is
     * back. Null must read as "nothing running", not as a veto, or the mode stays dead for good.
     */
    @Test
    fun `a user exit with the launcher nulled still re-arms when the phone comes back`() {
        val actions = actions(mode = WifiLauncherMode.NATIVE, wirelessArmed = false, groupUp = true)
        assertTrue(actions.forceRearmWireless)
        assertTrue(actions.clearUserExit)
    }

    @Test
    fun `only the Native mode setting forces a re-arm`() {
        for (mode in WifiLauncherMode.entries) {
            val expected = mode == WifiLauncherMode.NATIVE
            assertEquals("mode=$mode", expected, actions(mode = mode).forceRearmWireless)
        }
    }

    /**
     * A handoff closes the AA listeners, so the handshake reads inactive all session. A later
     * ACL_CONNECTED must not tear a working session down to re-arm it, in any mode.
     */
    @Test
    fun `a live or connecting session is never torn down by an ACL_CONNECTED`() {
        for (mode in WifiLauncherMode.entries) {
            assertTrue("mode=$mode", actions(mode = mode, sessionUp = true).doesNothing)
        }
    }

    @Test
    fun `a live session is never torn down even with no group`() {
        assertTrue(actions(sessionUp = true, handshakeActive = false, groupUp = false).doesNothing)
    }

    @Test
    fun `an active handshake with a live group is left alone`() {
        assertTrue(actions(handshakeActive = true, attemptInFlight = false, groupUp = true).doesNothing)
    }

    @Test
    fun `an attempt in flight is left alone`() {
        assertTrue(actions(handshakeActive = false, attemptInFlight = true).doesNothing)
    }

    /**
     * The state every reconnect used to pay for: armed, listening and a group up, and the phone's
     * arrival rebuilt all of it. Our own poke's ACL echo lands here too, which is the self-wake loop.
     */
    @Test
    fun `an armed Native mode with a live group is left alone`() {
        assertTrue(actions(handshakeActive = true, attemptInFlight = false, groupUp = true).doesNothing)
    }

    /** The join watchdog gave up, or WiFi went off: the listeners are open with nothing to join. */
    @Test
    fun `an armed Native mode whose group is gone is re-armed`() {
        assertTrue(actions(handshakeActive = true, attemptInFlight = false, groupUp = false).forceRearmWireless)
    }

    /** The hotspot transport has no group of ours to ask about, so the listeners decide. */
    @Test
    fun `a route with no group to ask about is left alone when its handshake is active`() {
        assertTrue(actions(handshakeActive = true, attemptInFlight = false, groupUp = null).doesNothing)
    }

    @Test
    fun `a route with no group to ask about is re-armed when its handshake is quiet`() {
        assertTrue(actions(handshakeActive = false, attemptInFlight = false, groupUp = null).forceRearmWireless)
    }

    /**
     * A launcher that exists but is fully quiet re-arms too: on a cold start the running handshake
     * blocks this, so a quiet one means something already stopped.
     */
    @Test
    fun `a present but quiet launcher does not block the Native re-arm`() {
        val actions = actions(wirelessArmed = true, handshakeActive = false, attemptInFlight = false)
        assertTrue(actions.forceRearmWireless)
    }

    @Test
    fun `the other wireless modes arm only when nothing is armed`() {
        for (mode in otherWirelessModes) {
            val actions = actions(mode = mode, wirelessArmed = false)
            assertTrue("mode=$mode", actions.armWirelessIfIdle)
            assertFalse("mode=$mode", actions.forceRearmWireless)
        }
    }

    @Test
    fun `a healthy group is never torn down to re-arm it`() {
        for (mode in otherWirelessModes) {
            val actions = actions(mode = mode, wirelessArmed = true)
            assertFalse("mode=$mode", actions.armWirelessIfIdle)
            assertFalse("mode=$mode", actions.forceRearmWireless)
            assertTrue("mode=$mode", actions.clearUserExit)
        }
    }

    /** The default wireless mode is Helper; a Self-only user must not get a Helper group armed. */
    @Test
    fun `a Self-only user gets no wireless launcher armed`() {
        for (mode in otherWirelessModes) {
            val actions = actions(mode = mode, wirelessSelected = false)
            assertFalse("mode=$mode", actions.armWirelessIfIdle)
            assertTrue("mode=$mode", actions.clearUserExit)
        }
    }

    /** Native's forced re-arm is not gated on the transport selection, so today's behaviour holds. */
    @Test
    fun `Native re-arms regardless of the transport selection`() {
        assertTrue(actions(wirelessSelected = false).forceRearmWireless)
    }

    private fun launchesSelfMode(
        selfSelected: Boolean = true,
        wirelessSelected: Boolean = false,
        mode: WifiLauncherMode = WifiLauncherMode.MANUAL,
        sessionUp: Boolean = false,
        nativeAttemptInFlight: Boolean? = null
    ) = BtAutoStartRearmPolicy.launchesSelfMode(
        selfSelected = selfSelected,
        wirelessSelected = wirelessSelected,
        mode = mode,
        sessionUp = sessionUp,
        nativeAttemptInFlight = nativeAttemptInFlight
    )

    @Test
    fun `Self Mode launches only with Self selected and no session`() {
        assertTrue(launchesSelfMode(selfSelected = true, sessionUp = false))
        assertFalse(launchesSelfMode(selfSelected = true, sessionUp = true))
        assertFalse(launchesSelfMode(selfSelected = false, sessionUp = false))
    }

    /**
     * The field failure: in Native mode the phone whose Bluetooth arrived is the source of the
     * wireless session, so its head unit server is not running and the launch cannot win. It armed
     * Self Mode anyway, and the failure stopped the wireless launcher mid-bring-up.
     */
    @Test
    fun `a Native wireless unit never launches Self Mode on a Bluetooth auto-start`() {
        assertFalse(launchesSelfMode(mode = WifiLauncherMode.NATIVE, wirelessSelected = true))
    }

    /** Native stored but wireless not selected leaves Self Mode as the only thing that could serve. */
    @Test
    fun `Native without the wireless transport selected still launches Self Mode`() {
        assertTrue(launchesSelfMode(mode = WifiLauncherMode.NATIVE, wirelessSelected = false))
    }

    /** Every other mode is unchanged: none of them dials our own loopback out from under itself. */
    @Test
    fun `the other wireless modes are unaffected by the Native veto`() {
        for (mode in otherWirelessModes) {
            assertTrue("mode=$mode", launchesSelfMode(mode = mode, wirelessSelected = true))
        }
    }

    /**
     * The poke's own socket.connect() raises the ACL_CONNECTED that brought us here, and
     * isAttemptInFlight() is the one signal that says so. Null means Native is not armed.
     */
    @Test
    fun `an attempt in flight never launches Self Mode`() {
        assertFalse(launchesSelfMode(nativeAttemptInFlight = true))
        assertTrue(launchesSelfMode(nativeAttemptInFlight = false))
        assertTrue(launchesSelfMode(nativeAttemptInFlight = null))
    }

    /**
     * The window this policy could not see: a network asked for and not yet answered reads as
     * "cannot accept" everywhere else, and rebuilding there starts a second create underneath.
     */
    @Test
    fun `a network still being created is left to answer`() {
        for (mode in WifiLauncherMode.entries) {
            assertTrue(
                "mode=$mode",
                actions(mode = mode, handshakeActive = false, attemptInFlight = false, groupUp = false, networkComingUp = true).doesNothing
            )
        }
    }

    /** The hotspot route has no network of ours, so the create question does not apply there. */
    @Test
    fun `a route with no network of ours to ask about still re-arms`() {
        val actions = actions(mode = WifiLauncherMode.NATIVE, groupUp = null, networkComingUp = null)
        assertTrue(actions.forceRearmWireless)
        assertTrue(actions.clearUserExit)
    }

}
