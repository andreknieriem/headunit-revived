package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStarvationPolicyTest {

    private fun streakAfter(vararg sessions: Pair<Boolean, Boolean>): Int =
        sessions.fold(0) { streak, (reachedHandshake, rendered) ->
            VideoStarvationPolicy.nextStreak(streak, reachedHandshake, rendered)
        }

    private val starved = true to false
    private val healthy = true to true
    private val neverConnected = false to false

    @Test
    fun `a starved session lengthens the streak`() {
        assertEquals(1, streakAfter(starved))
        assertEquals(3, streakAfter(starved, starved, starved))
    }

    @Test
    fun `one rendered frame clears it`() {
        assertEquals(0, streakAfter(starved, starved, healthy))
    }

    @Test
    fun `a session that never handshook neither counts nor clears`() {
        assertEquals(2, streakAfter(starved, starved, neverConnected))
        assertEquals(0, streakAfter(neverConnected, neverConnected))
    }

    @Test
    fun `advises only once the run is long enough to mean something`() {
        assertFalse(VideoStarvationPolicy.shouldAdvise(0))
        assertFalse(VideoStarvationPolicy.shouldAdvise(1))
        assertFalse(VideoStarvationPolicy.shouldAdvise(2))
        assertTrue(VideoStarvationPolicy.shouldAdvise(3))
    }

    @Test
    fun `says it once, not on every reconnect after`() {
        // The 2.4GHz measurement produced 32 of these in under five minutes.
        val advised = (1..32).count { VideoStarvationPolicy.shouldAdvise(it) }

        assertEquals(1, advised)
    }

    @Test
    fun `a cleared streak can advise again on the next run`() {
        var streak = streakAfter(starved, starved, starved)
        assertTrue(VideoStarvationPolicy.shouldAdvise(streak))

        streak = VideoStarvationPolicy.nextStreak(streak, reachedHandshake = true, renderedAnyFrame = true)
        assertEquals(0, streak)

        repeat(VideoStarvationPolicy.ADVISE_AFTER_STARVED_SESSIONS) {
            streak = VideoStarvationPolicy.nextStreak(streak, reachedHandshake = true, renderedAnyFrame = false)
        }
        assertTrue(VideoStarvationPolicy.shouldAdvise(streak))
    }
}
