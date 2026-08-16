package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleLinkProbePolicyTest {

    /**
     * The regression this policy exists for: a single silent read window used to end the session.
     * On WiFi a 15 s stall is ordinary — a scan, a power-save wake, a retransmission burst — and
     * treating the first one as a dead link is what dropped sessions mid-drive.
     */
    @Test
    fun `the first silent window probes instead of disconnecting`() {
        assertEquals(IdleLinkAction.PROBE, IdleLinkProbePolicy.onSilentReadWindow(0))
    }

    @Test
    fun `silence keeps probing while the budget lasts`() {
        for (unanswered in 0 until IdleLinkProbePolicy.MAX_UNANSWERED_PROBES) {
            assertEquals(
                "unansweredProbes=$unanswered",
                IdleLinkAction.PROBE,
                IdleLinkProbePolicy.onSilentReadWindow(unanswered)
            )
        }
    }

    /**
     * The other half: the policy must still let go. A peer that has ignored every probe is gone,
     * and holding the session open would leave the user on a frozen screen with no reconnect.
     */
    @Test
    fun `a peer that ignores every probe is declared dead`() {
        assertEquals(
            IdleLinkAction.TEAR_DOWN,
            IdleLinkProbePolicy.onSilentReadWindow(IdleLinkProbePolicy.MAX_UNANSWERED_PROBES)
        )
    }

    @Test
    fun `teardown stays latched once the budget is spent`() {
        // The read loop only resets the counter on inbound bytes, but nothing should depend on it
        // never overshooting - an extra probe in flight must not reopen the session.
        for (overshoot in 0..3) {
            assertEquals(
                "unansweredProbes=${IdleLinkProbePolicy.MAX_UNANSWERED_PROBES + overshoot}",
                IdleLinkAction.TEAR_DOWN,
                IdleLinkProbePolicy.onSilentReadWindow(IdleLinkProbePolicy.MAX_UNANSWERED_PROBES + overshoot)
            )
        }
    }

    /**
     * Guards the tuning, not the logic. Detection has to stay inside the time it takes a user to
     * look at the screen and decide the app is broken; a budget large enough to push teardown past
     * a couple of minutes would trade one bad failure mode for another.
     */
    @Test
    fun `the probe budget keeps detection under two minutes`() {
        val readWindowMs = 15_000
        val worstCaseMs = (IdleLinkProbePolicy.MAX_UNANSWERED_PROBES + 1) * readWindowMs
        assertEquals(
            "budget should leave detection around a minute",
            60_000,
            worstCaseMs
        )
    }
}
