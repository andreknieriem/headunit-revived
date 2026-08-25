package com.andrerinas.openheadunit.aap

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
     * stayed dead for the life of the process however often the phone reconnected.
     */
    @Test
    fun `a user exit with the launcher nulled still re-arms when the phone comes back`() {
        assertTrue(
            BtAutoStartRearmPolicy.shouldRearm(
                mode = WifiLauncherMode.NATIVE,
                sessionUp = false,
                handshakeActive = null,
                attemptInFlight = null
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
                attemptInFlight = null
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
                attemptInFlight = null
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
                attemptInFlight = false
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
                attemptInFlight = true
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
                attemptInFlight = false
            )
        )
    }
}
