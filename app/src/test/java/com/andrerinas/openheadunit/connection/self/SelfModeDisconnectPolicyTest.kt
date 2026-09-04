package com.andrerinas.openheadunit.connection.self

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfModeDisconnectPolicyTest {

    /**
     * The field failure: SelfLauncherV17_4's own failed dial to 127.0.0.1:5277 arrived as a
     * disconnect, and the wireless launcher was stopped underneath a group that was coming up.
     */
    @Test
    fun `a disconnect while the launchers are still running stops nothing`() {
        assertFalse(
            SelfModeDisconnectPolicy.stopsWirelessLauncher(
                selfModeActive = true,
                launchInFlight = true
            )
        )
    }

    /** A Self Mode session that ran and ended still owns the wireless launcher's teardown. */
    @Test
    fun `a disconnect after the launch finished stops the wireless launcher`() {
        assertTrue(
            SelfModeDisconnectPolicy.stopsWirelessLauncher(
                selfModeActive = true,
                launchInFlight = false
            )
        )
    }

    /** Nothing to decide when Self Mode was never armed; the caller takes its other branches. */
    @Test
    fun `Self Mode not armed decides nothing`() {
        assertFalse(
            SelfModeDisconnectPolicy.stopsWirelessLauncher(
                selfModeActive = false,
                launchInFlight = false
            )
        )
        assertFalse(
            SelfModeDisconnectPolicy.stopsWirelessLauncher(
                selfModeActive = false,
                launchInFlight = true
            )
        )
    }
}
