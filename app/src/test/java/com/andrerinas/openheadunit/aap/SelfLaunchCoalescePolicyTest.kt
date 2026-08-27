package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfLaunchCoalescePolicyTest {

    @Test
    fun `an idle request with nothing connected is the one that starts a launch`() {
        assertTrue(SelfLaunchCoalescePolicy.shouldStart(launchInFlight = false, isConnected = false))
    }

    @Test
    fun `a second request while one is in flight does not start another`() {
        // The defect: auto-start-self-mode and an explicit ACTION_START_SELF_MODE both fired, and on
        // the 17.4+ route the loser's emitError disconnected the winner's session.
        assertFalse(SelfLaunchCoalescePolicy.shouldStart(launchInFlight = true, isConnected = false))
    }

    @Test
    fun `a request arriving on a live session has nothing to do`() {
        assertFalse(SelfLaunchCoalescePolicy.shouldStart(launchInFlight = false, isConnected = true))
        assertFalse(SelfLaunchCoalescePolicy.shouldStart(launchInFlight = true, isConnected = true))
    }

    @Test
    fun `running out of launchers is reportable only while nothing has connected`() {
        assertTrue(SelfLaunchCoalescePolicy.mayReportAllLaunchersFailed(isConnected = false))
        assertFalse(SelfLaunchCoalescePolicy.mayReportAllLaunchersFailed(isConnected = true))
    }

    @Test
    fun `the two rules agree that a connected session is nobody's to restart or end`() {
        // Both guards have to hold for the measured failure: the coalesce stops the second launch,
        // and the report guard covers a duplicate that reached the launchers some other way.
        assertFalse(SelfLaunchCoalescePolicy.shouldStart(launchInFlight = false, isConnected = true))
        assertFalse(SelfLaunchCoalescePolicy.mayReportAllLaunchersFailed(isConnected = true))
    }
}
