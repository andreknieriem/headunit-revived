package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pInterfaceChurnPolicyTest {

    private val quiet = P2pInterfaceChurnPolicy.QUIET_AFTER_OUR_REQUEST_MS

    @Test
    fun `an edge inside our own request window is not attributed to anyone else`() {
        assertFalse(P2pInterfaceChurnPolicy.countsAsForeign(1_000L, 1_000L))
        assertFalse(P2pInterfaceChurnPolicy.countsAsForeign(1_000L + quiet - 1, 1_000L))
    }

    @Test
    fun `an edge once our request window has passed is foreign`() {
        assertTrue(P2pInterfaceChurnPolicy.countsAsForeign(1_000L + quiet, 1_000L))
        assertTrue(P2pInterfaceChurnPolicy.countsAsForeign(1_000L + quiet * 10, 1_000L))
    }

    @Test
    fun `churn before we have asked the platform for anything is foreign`() {
        assertTrue(P2pInterfaceChurnPolicy.countsAsForeign(1_000L, 0L))
    }

    @Test
    fun `a stamp in the future is treated as ours rather than accusing anyone`() {
        assertFalse(P2pInterfaceChurnPolicy.countsAsForeign(1_000L, 9_000L))
    }

    @Test
    fun `the threshold separates a driver reload from a cycled stack`() {
        assertFalse(
            P2pInterfaceChurnPolicy.isForeignChurn(P2pInterfaceChurnPolicy.FOREIGN_CHURN_PER_WINDOW - 1)
        )
        assertTrue(
            P2pInterfaceChurnPolicy.isForeignChurn(P2pInterfaceChurnPolicy.FOREIGN_CHURN_PER_WINDOW)
        )
        // The reporter's unit, measured: ~450 foreign edges in one window.
        assertTrue(P2pInterfaceChurnPolicy.isForeignChurn(450))
    }

    @Test
    fun `a quiet window is never churn`() {
        assertFalse(P2pInterfaceChurnPolicy.isForeignChurn(0))
    }

    @Test
    fun `the log quota keeps the first few edges of a window and drops the rest`() {
        assertTrue(P2pInterfaceChurnPolicy.shouldLogEachBroadcast(1))
        assertTrue(
            P2pInterfaceChurnPolicy.shouldLogEachBroadcast(P2pInterfaceChurnPolicy.LOG_QUOTA_PER_WINDOW)
        )
        assertFalse(
            P2pInterfaceChurnPolicy.shouldLogEachBroadcast(P2pInterfaceChurnPolicy.LOG_QUOTA_PER_WINDOW + 1)
        )
        assertFalse(P2pInterfaceChurnPolicy.shouldLogEachBroadcast(23_183))
    }

    @Test
    fun `the first report is never held back`() {
        assertTrue(P2pInterfaceChurnPolicy.shouldReport(1_000L, 0L))
    }

    @Test
    fun `a report waits out its interval`() {
        val interval = P2pInterfaceChurnPolicy.REPORT_INTERVAL_MS
        assertFalse(P2pInterfaceChurnPolicy.shouldReport(1_000L + interval - 1, 1_000L))
        assertTrue(P2pInterfaceChurnPolicy.shouldReport(1_000L + interval, 1_000L))
    }

    @Test
    fun `a clock that moved backwards reports rather than going quiet for a minute`() {
        assertTrue(P2pInterfaceChurnPolicy.shouldReport(1_000L, 9_000L))
    }
}
