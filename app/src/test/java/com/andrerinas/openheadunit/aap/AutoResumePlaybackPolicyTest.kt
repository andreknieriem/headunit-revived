package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoResumePlaybackPolicyTest {

    @Test
    fun `when setting is disabled, never resumes playback`() {
        assertFalse(
            AutoResumePlaybackPolicy.shouldResume(
                enabled = false,
                wasPlayingBeforeDisconnect = true,
                elapsedSinceDisconnectMs = 5_000L
            )
        )
    }

    @Test
    fun `when music was paused before disconnect, never resumes playback`() {
        assertFalse(
            AutoResumePlaybackPolicy.shouldResume(
                enabled = true,
                wasPlayingBeforeDisconnect = false,
                elapsedSinceDisconnectMs = 5_000L
            )
        )
    }

    @Test
    fun `when quick reconnect occurs within window and music was playing, resumes playback`() {
        assertTrue(
            AutoResumePlaybackPolicy.shouldResume(
                enabled = true,
                wasPlayingBeforeDisconnect = true,
                elapsedSinceDisconnectMs = 3_000L
            )
        )
        assertTrue(
            AutoResumePlaybackPolicy.shouldResume(
                enabled = true,
                wasPlayingBeforeDisconnect = true,
                elapsedSinceDisconnectMs = 60_000L
            )
        )
    }

    @Test
    fun `when reconnect occurs after timeout, does not resume playback`() {
        assertFalse(
            AutoResumePlaybackPolicy.shouldResume(
                enabled = true,
                wasPlayingBeforeDisconnect = true,
                elapsedSinceDisconnectMs = 60_001L
            )
        )
        assertFalse(
            AutoResumePlaybackPolicy.shouldResume(
                enabled = true,
                wasPlayingBeforeDisconnect = true,
                elapsedSinceDisconnectMs = 300_000L
            )
        )
    }

    @Test
    fun `when elapsed time is invalid or non-positive, does not resume playback`() {
        assertFalse(
            AutoResumePlaybackPolicy.shouldResume(
                enabled = true,
                wasPlayingBeforeDisconnect = true,
                elapsedSinceDisconnectMs = 0L
            )
        )
        assertFalse(
            AutoResumePlaybackPolicy.shouldResume(
                enabled = true,
                wasPlayingBeforeDisconnect = true,
                elapsedSinceDisconnectMs = -1_000L
            )
        )
    }
}
