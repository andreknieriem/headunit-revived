package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.VideoFocusReleasePolicy.TOUCH_WINDOW_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFocusReleasePolicyTest {

    @Test
    fun `a cover with no touch behind it always releases`() {
        // The regression guard. This is Home, a notification, an alarm and every real teardown,
        // and the release is what brings the picture back in 42-96 ms instead of seconds.
        assertTrue(release(coverFollowsTouch = false))
        assertTrue(release(coverFollowsTouch = false, sessionConnected = false))
        assertTrue(release(coverFollowsTouch = false, pipActive = true))
    }

    @Test
    fun `a keyboard-shaped cover holds focus`() {
        assertFalse(release())
    }

    @Test
    fun `there is nothing to protect without a session`() {
        assertTrue(release(sessionConnected = false))
    }

    @Test
    fun `an activity that is going away tells the phone`() {
        assertTrue(release(activityEnding = true))
    }

    @Test
    fun `picture-in-picture is covered on purpose`() {
        assertTrue(release(pipActive = true))
    }

    @Test
    fun `a cycle already holding focus released is left alone`() {
        // Withholding would change nothing - focus is already released - and the cycle owns the
        // regain that follows.
        assertTrue(release(focusCycleInFlight = true))
    }

    @Test
    fun `the touch window is inclusive at its edge`() {
        val start = 10_000L
        assertTrue(VideoFocusReleasePolicy.coverFollowsTouch(start, start + TOUCH_WINDOW_MS))
        assertFalse(VideoFocusReleasePolicy.coverFollowsTouch(start, start + TOUCH_WINDOW_MS + 1))
    }

    @Test
    fun `a session that has never forwarded a touch has no touch to follow`() {
        assertFalse(VideoFocusReleasePolicy.coverFollowsTouch(lastTouchAtMs = 0L, coveredAtMs = 10_000L))
    }

    private fun release(
        coverFollowsTouch: Boolean = true,
        sessionConnected: Boolean = true,
        activityEnding: Boolean = false,
        pipActive: Boolean = false,
        focusCycleInFlight: Boolean = false,
    ): Boolean = VideoFocusReleasePolicy.shouldReleaseOnSurfaceLost(
        coverFollowsTouch = coverFollowsTouch,
        sessionConnected = sessionConnected,
        activityEnding = activityEnding,
        pipActive = pipActive,
        focusCycleInFlight = focusCycleInFlight,
    )
}
