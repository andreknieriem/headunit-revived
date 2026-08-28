package com.andrerinas.openheadunit.decoder.video

/**
 * How long the transport's read thread may wait for space in the feed queue before a frame is shed.
 *
 * A full queue used to shed the arriving access unit immediately. On a decoder running at its
 * ceiling - decoding and rendering continuously, just slower than the phone sends - that turned
 * every arrival burst into shed units, and every unit in this stream is a reference frame, so each
 * one cost a washed-out, blocky picture until the phone's own keyframe, tens of seconds away.
 * Measured on an MT6735 at 1080p60: two bursts of ~50 shed frames each, artifacts for the rest of
 * the session.
 *
 * Waiting here instead is the protocol's own flow control. Media acks are sent after a message is
 * processed, and the phone stops sending once its unacked-message window fills - so a read thread
 * paced by this wait closes that window within a handful of messages and the phone throttles
 * itself to the rate the codec actually drains. That is how the pipeline behaved before the feed
 * queue existed, and the same hardware ran artifact-free then. The queue keeps its own job: a
 * burst after a link stall still lands in it whole and is caught up at render time, and in steady
 * state at the codec's ceiling the wait per frame is one frame period, not the budget.
 *
 * The wait is bounded because a codec can also be wedged rather than slow. Past [WAIT_BUDGET_MS]
 * the frame is shed exactly as before, and the wedge belongs to the sync_stall watchdog.
 */
object VideoFeedThrottlePolicy {

    /**
     * One timed offer on the queue. Between slices the caller re-checks whether the decoder is
     * still running, so a teardown aborts the wait within one slice rather than holding the stop
     * to the full budget.
     */
    const val OFFER_SLICE_MS = 50L

    /**
     * Total per-frame bound on the read thread.
     *
     * Must clear [VideoFeedQueuePolicy.INPUT_DEQUEUE_PATIENCE_MS]: the feed thread gives up on a
     * frame after that long and frees a slot, so any codec still draining at all - however slowly -
     * frees space inside this budget, and expiry genuinely means the feed side is stuck inside the
     * codec. Must stay under the 2s input-idle threshold the stall-cause classifier reads (a paced
     * read thread still stamps input liveness once per frame) and far under the 10s link-quiet
     * window the projection watchdog treats as a dead link.
     */
    const val WAIT_BUDGET_MS = 1_000L

    /** Whether the enqueue should keep waiting for space after [elapsedMs] on a live decoder. */
    fun shouldKeepWaiting(elapsedMs: Long, running: Boolean): Boolean =
        running && elapsedMs < WAIT_BUDGET_MS
}
