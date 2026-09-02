package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtAutoStartRearmPolicyTest {

    /**
     * The state this policy exists for: a Native user exit stopped the launcher and nulled it,
     * and the phone has come back over Bluetooth. A null launcher answers null to both handshake
     * questions, and that must read as "nothing running", not as a veto. The regression this pins
     * read the mode off the null launcher and answered no in exactly this state, so the mode
     * stayed dead for the life of the process however often the phone reconnected. A group left
     * behind by a force-stop must not block it either.
     */
    @Test
    fun `a user exit with the launcher nulled still re-arms when the phone comes back`() {
        assertTrue(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = false,
                handshakeActive = null,
                attemptInFlight = null,
                groupUp = true
            )
        )
    }

    @Test
    fun `only the Native mode setting re-arms`() {
        for (mode in WifiLauncherMode.entries) {
            val expected = mode == WifiLauncherMode.NATIVE
            val actual = BtAutoStartRearmPolicy.shouldRearm(
                mode = mode,
                sessionUp = false,
                handshakeActive = null,
                attemptInFlight = null,
                groupUp = false
            )
            assertTrue("mode=$mode", expected == actual)
        }
    }

    /**
     * A successful handoff closes the AA listeners, so the handshake reads inactive for the whole
     * life of a working session. Any later ACL_CONNECTED - the phone's own profiles reconnecting,
     * or one of our own pokes - must not tear the session down to re-arm.
     */
    @Test
    fun `a live or connecting session is never torn down by an ACL_CONNECTED`() {
        assertFalse(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = true,
                handshakeActive = null,
                attemptInFlight = null,
                groupUp = false
            )
        )
    }

    @Test
    fun `an active handshake is left alone`() {
        assertFalse(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = false,
                handshakeActive = true,
                attemptInFlight = false,
                groupUp = null
            )
        )
    }

    @Test
    fun `an attempt in flight is left alone`() {
        assertFalse(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = false,
                handshakeActive = false,
                attemptInFlight = true,
                groupUp = false
            )
        )
    }

    /**
     * A launcher that exists but is fully quiet re-arms too: on a genuine cold start the running
     * handshake reads active and blocks this, so the quiet case only arises when something has
     * already stopped, which is the case the re-arm is for.
     */
    @Test
    fun `a present but quiet launcher does not block the re-arm`() {
        assertTrue(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = false,
                handshakeActive = false,
                attemptInFlight = false,
                groupUp = true
            )
        )
    }

    /**
     * The state every reconnect used to pay for: the mode is armed, its listeners are open and
     * its group is up, and the phone's arrival tore all of it down to build the same thing again.
     * On a unit that re-addresses the group that also invalidated the profile the phone saved.
     * Our own poke's ACL echo lands here too, which is the self-wake loop.
     */
    @Test
    fun `an armed Native mode with a live group is left alone`() {
        assertFalse(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = false,
                handshakeActive = true,
                attemptInFlight = false,
                groupUp = true
            )
        )
    }

    /** The join watchdog gave up, or WiFi went off: the listeners are open with nothing to join. */
    @Test
    fun `an armed Native mode whose group is gone is re-armed`() {
        assertTrue(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = false,
                handshakeActive = true,
                attemptInFlight = false,
                groupUp = false
            )
        )
    }

    /** The hotspot transport has no group of ours to ask about, so the listeners decide. */
    @Test
    fun `a route with no group to ask about is left alone when it is armed`() {
        assertFalse(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = false,
                handshakeActive = true,
                attemptInFlight = false,
                groupUp = null
            )
        )
    }

    @Test
    fun `a route with no group to ask about is re-armed when it is not`() {
        assertTrue(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = false,
                handshakeActive = false,
                attemptInFlight = false,
                groupUp = null
            )
        )
    }

    @Test
    fun `a live session is never torn down even with no group`() {
        assertFalse(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = true,
                handshakeActive = false,
                attemptInFlight = false,
                groupUp = false
            )
        )
    }
}
