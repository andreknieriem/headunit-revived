package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.ProjectionWatchdogPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enqueue wait exists to pace the transport's read thread against the codec's real drain rate,
 * and every watchdog that observes that thread has an opinion about how long it may pause. These
 * tests pin the budget inside all of them, so a throttled-but-healthy session can never be read as
 * an idle phone, a dead link, or a stalled decoder.
 */
class VideoFeedThrottlePolicyTest {

    @Test
    fun `a slice is shorter than the whole budget`() {
        // The slice is what bounds how long a teardown waits for the enqueue to notice
        // running=false; a slice as long as the budget would make the re-check decorative.
        assertTrue(VideoFeedThrottlePolicy.OFFER_SLICE_MS < VideoFeedThrottlePolicy.WAIT_BUDGET_MS)
    }

    @Test
    fun `the budget outlasts the feed thread's give-up, so expiry means a wedge`() {
        // The feed thread abandons a frame after INPUT_DEQUEUE_PATIENCE_MS and frees a slot. As
        // long as the budget clears that, a merely-slow codec always admits the waiting frame,
        // and budget expiry is reserved for a codec that took nothing for the whole wait.
        assertTrue(
            VideoFeedThrottlePolicy.WAIT_BUDGET_MS > VideoFeedQueuePolicy.INPUT_DEQUEUE_PATIENCE_MS
        )
    }

    @Test
    fun `the budget stays under the stall-cause classifier's input-idle threshold`() {
        // VideoDecoder's SYNC_STALL_THRESHOLD_MS (private, 2000) doubles as the input-idle window
        // DecoderStallCausePolicy reads. lastInputBytesReceivedMs is stamped once per decode()
        // call, so the longest it can age while the read thread is paced is one budget - which
        // must not be long enough to make arriving input classify as PHONE_IDLE.
        assertTrue(VideoFeedThrottlePolicy.WAIT_BUDGET_MS < 2_000L)
    }

    @Test
    fun `the budget stays far under the projection watchdog's link-quiet window`() {
        // A paced read thread delays every channel's dispatch by at most one budget; the
        // reconnecting overlay needs the link quiet for LINK_QUIET_MS to show. These must not be
        // in the same order of magnitude.
        assertTrue(
            VideoFeedThrottlePolicy.WAIT_BUDGET_MS * 5 <= ProjectionWatchdogPolicy.LINK_QUIET_MS
        )
    }

    @Test
    fun `waits while running and inside the budget`() {
        assertTrue(VideoFeedThrottlePolicy.shouldKeepWaiting(0L, running = true))
        assertTrue(
            VideoFeedThrottlePolicy.shouldKeepWaiting(
                VideoFeedThrottlePolicy.WAIT_BUDGET_MS - 1, running = true
            )
        )
    }

    @Test
    fun `stops at the budget`() {
        assertFalse(
            VideoFeedThrottlePolicy.shouldKeepWaiting(
                VideoFeedThrottlePolicy.WAIT_BUDGET_MS, running = true
            )
        )
        assertFalse(
            VideoFeedThrottlePolicy.shouldKeepWaiting(
                VideoFeedThrottlePolicy.WAIT_BUDGET_MS + 1, running = true
            )
        )
    }

    @Test
    fun `never waits on a stopped decoder`() {
        assertFalse(VideoFeedThrottlePolicy.shouldKeepWaiting(0L, running = false))
        assertFalse(
            VideoFeedThrottlePolicy.shouldKeepWaiting(
                VideoFeedThrottlePolicy.WAIT_BUDGET_MS - 1, running = false
            )
        )
    }

    @Test
    fun `a clock that runs backwards is treated as inside the budget, not as overflow`() {
        // elapsed is a difference of two elapsedRealtime() reads and cannot really go negative,
        // but a negative value must fail safe into "keep waiting" rather than into arithmetic
        // surprise - the running flag is the abort path, not the clock.
        assertTrue(VideoFeedThrottlePolicy.shouldKeepWaiting(-1L, running = true))
    }
}
