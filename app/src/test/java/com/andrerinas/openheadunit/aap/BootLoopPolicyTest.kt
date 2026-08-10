package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootLoopPolicyTest {

    /**
     * Replays a run of boot-started lifetimes in milliseconds and returns the strike count left
     * behind, the way the receiver and the service between them maintain it: every boot-started run
     * takes a strike on the way in, and clears the count if it survives long enough.
     */
    private fun strikesAfter(vararg runLengthsMs: Long): Int =
        runLengthsMs.fold(0) { strikes, runMs ->
            val taken = BootLoopPolicy.nextStrikes(strikes)
            if (BootLoopPolicy.clearsStrikes(runMs)) 0 else taken
        }

    @Test
    fun `strikes accumulate across runs that die young`() {
        assertEquals(1, strikesAfter(8_000))
        assertEquals(2, strikesAfter(8_000, 9_000))
        assertEquals(3, strikesAfter(8_000, 9_000, 10_000))
    }

    @Test
    fun `the third short run pauses wireless, the first two do not`() {
        assertFalse(BootLoopPolicy.shouldPauseWireless(strikesAfter(8_000)))
        assertFalse(BootLoopPolicy.shouldPauseWireless(strikesAfter(8_000, 9_000)))
        assertTrue(BootLoopPolicy.shouldPauseWireless(strikesAfter(8_000, 9_000, 10_000)))
    }

    @Test
    fun `a run that survives clears the count`() {
        assertEquals(0, strikesAfter(8_000, 9_000, 60_000))
        assertFalse(BootLoopPolicy.shouldPauseWireless(strikesAfter(8_000, 9_000, 60_000)))
    }

    @Test
    fun `the healthy threshold is a floor, not a target`() {
        assertFalse(BootLoopPolicy.clearsStrikes(BootLoopPolicy.HEALTHY_RUN_MS - 1))
        assertTrue(BootLoopPolicy.clearsStrikes(BootLoopPolicy.HEALTHY_RUN_MS))
    }

    @Test
    fun `the reported crash loop pauses wireless on the fourth boot`() {
        // Process lifetimes measured from HUR_Log_20260804: 173.3 s, which reached a complete
        // projection session with audio before the system died, then 7.8 s and 9.2 s. The guard
        // is read at the start of a run, so the fourth process is the one that comes up paused.
        val beforeFourthBoot = strikesAfter(173_300, 7_800, 9_200)

        assertEquals(2, beforeFourthBoot)
        assertTrue(BootLoopPolicy.shouldPauseWireless(BootLoopPolicy.nextStrikes(beforeFourthBoot)))
    }

    @Test
    fun `the 3 2 1 log ends one cycle short of the guard`() {
        // HUR_Log_20260805, same unit: 48.1 s, 9.8 s, 15.2 s, and there the reporter stopped
        // recording. The 48 s run clears, so the export runs out two strikes in. Recorded as
        // measured rather than padded to a trip — the third failure is not in the log, and this
        // test exists to replay what was observed.
        val atEndOfLog = strikesAfter(48_100, 9_800, 15_200)

        assertEquals(2, atEndOfLog)
        assertFalse(BootLoopPolicy.shouldPauseWireless(atEndOfLog))
        assertTrue(BootLoopPolicy.shouldPauseWireless(BootLoopPolicy.nextStrikes(atEndOfLog)))
    }

    @Test
    fun `an ordinary run of short trips does not trip it`() {
        // Every trip long enough to clear on its own, however many there are.
        assertEquals(0, strikesAfter(45_000, 90_000, 31_000, 120_000))
    }

    @Test
    fun `a single bad boot between good ones is forgiven`() {
        assertEquals(0, strikesAfter(60_000, 5_000, 60_000))
        assertFalse(BootLoopPolicy.shouldPauseWireless(strikesAfter(60_000, 5_000, 60_000)))
    }

    @Test
    fun `a corrupt or absent stored count starts a fresh run`() {
        assertEquals(1, BootLoopPolicy.nextStrikes(-4))
        assertEquals(1, BootLoopPolicy.nextStrikes(0))
    }
}
