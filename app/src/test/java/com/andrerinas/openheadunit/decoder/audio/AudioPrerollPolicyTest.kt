package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the target on both a deep and a shallow buffer, and the two ways the wait must end early: a
 * stream too short to reach the target, and a chunk whose write would deadlock the thread that
 * starts playback.
 */
class AudioPrerollPolicyTest {

    private val mediaRate = 48_000
    private val speechRate = 16_000

    /** 48 kHz stereo, the multiplier-8 media buffer measured on the reporter's unit. */
    private val mediaBufferFrames = 33_536

    @Test
    fun `the target is the time ceiling on an ordinarily deep buffer`() {
        // 48000 * 200ms = 9600 frames, well inside three quarters of 33536.
        assertEquals(9_600, AudioPrerollPolicy.targetFrames(mediaRate, mediaBufferFrames))
    }

    @Test
    fun `a shallow buffer caps the target below the time ceiling`() {
        // Multiplier 1 at 16 kHz mono: 1408 frames. The time ceiling of 3200 exceeds the track, so
        // the fill share has to win or the target is unreachable.
        val target = AudioPrerollPolicy.targetFrames(speechRate, 1_408)
        assertEquals(1_056, target)
        assertTrue(target < 1_408)
    }

    @Test
    fun `the target always leaves room for the write that triggers it`() {
        // The write that starts playback must still fit. A target at capacity would block in
        // write() waiting for room only play() can make, from the same thread.
        for (frames in intArrayOf(64, 512, 1_408, 4_192, 33_536)) {
            val target = AudioPrerollPolicy.targetFrames(mediaRate, frames)
            assertTrue("target $target must stay under capacity $frames", target < frames)
        }
    }

    @Test
    fun `a nonsense capacity still yields a playable target`() {
        assertTrue(AudioPrerollPolicy.targetFrames(mediaRate, 0) > 0)
        assertTrue(AudioPrerollPolicy.targetFrames(mediaRate, -1) > 0)
        assertTrue(AudioPrerollPolicy.targetFrames(0, mediaBufferFrames) > 0)
    }

    @Test
    fun `an empty track does not start`() {
        // The whole point: this is the state that underran on every media start.
        assertFalse(AudioPrerollPolicy.shouldStart(0, 0, 9_600, 0))
    }

    @Test
    fun `a partly filled track does not start before its target`() {
        assertFalse(AudioPrerollPolicy.shouldStart(9_000, 0, 9_600, 10))
    }

    @Test
    fun `reaching the target starts playback`() {
        assertTrue(AudioPrerollPolicy.shouldStart(9_600, 0, 9_600, 10))
    }

    @Test
    fun `the pending chunk counts toward the target`() {
        // Decided before the write, so the frames about to land are part of the decision.
        assertFalse(AudioPrerollPolicy.shouldStart(9_000, 0, 9_600, 10))
        assertTrue(AudioPrerollPolicy.shouldStart(9_000, 600, 9_600, 10))
    }

    @Test
    fun `a stream too short for the target starts on the deadline`() {
        // A notification blip can be the whole message; waiting for an unreachable target would
        // silence it.
        assertFalse(AudioPrerollPolicy.shouldStart(500, 0, 9_600, AudioPrerollPolicy.MAX_WAIT_MS - 1))
        assertTrue(AudioPrerollPolicy.shouldStart(500, 0, 9_600, AudioPrerollPolicy.MAX_WAIT_MS))
    }

    @Test
    fun `the deadline never starts a track nothing has been written to`() {
        // A stream that has not begun, not a short one. Starting here reintroduces the bug.
        assertFalse(AudioPrerollPolicy.shouldStart(0, 0, 9_600, AudioPrerollPolicy.MAX_WAIT_MS * 10))
    }

    @Test
    fun `an ordinary stream reaches its target by fill and not by deadline`() {
        // Audio arrives at real time, so the target takes its own worth of wall clock. A deadline
        // inside that would decide every start and the banked depth would be arbitrary.
        assertTrue(AudioPrerollPolicy.MAX_WAIT_MS > AudioPrerollPolicy.TARGET_MS)
    }

    @Test
    fun `the banked depth is what the latency multiplier was asked to buy`() {
        // Below the ~87 ms device minimum the setting cannot cushion anything.
        assertTrue(AudioPrerollPolicy.TARGET_MS > 87)
        // Above ~250 ms a resume stops feeling attached to the press that caused it.
        assertTrue(AudioPrerollPolicy.TARGET_MS <= 250)
    }
}
