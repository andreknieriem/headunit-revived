package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.decoder.video.PictureCredibilityPolicy.PENDING_KEYFRAME_GRACE_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureCredibilityPolicyTest {

    @Test
    fun `a surface with nothing rendered has no picture`() {
        for (accounting in listOf(true, false)) {
            for (decoded in listOf(true, false)) {
                for (pending in listOf(0L, 500L, Long.MAX_VALUE)) {
                    assertFalse(
                        credible(
                            rendered = false,
                            accounting = accounting,
                            decoded = decoded,
                            pendingAgeMs = pending,
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `a stream rendering after a decoded keyframe has a picture`() {
        // The regression guard. If this ever answers false, every healthy stream earns a focus
        // cycle it does not need.
        assertTrue(credible(decoded = true, pendingAgeMs = Long.MAX_VALUE))
        assertTrue(credible(decoded = true, pendingAgeMs = 10L))
    }

    @Test
    fun `frames decoded from P-frames alone are not a picture`() {
        // The measured defect: a live view-mode switch rebuilds the codec from cached parameter
        // sets, it emits gray output, and no keyframe has been anywhere near it.
        assertFalse(credible(decoded = false, pendingAgeMs = Long.MAX_VALUE))
    }

    @Test
    fun `a keyframe still working its way to the output is given the grace`() {
        assertTrue(credible(decoded = false, pendingAgeMs = 100L))
        assertTrue(credible(decoded = false, pendingAgeMs = PENDING_KEYFRAME_GRACE_MS - 1))
    }

    @Test
    fun `a keyframe that produced nothing stops counting after the grace`() {
        assertFalse(credible(decoded = false, pendingAgeMs = PENDING_KEYFRAME_GRACE_MS + 1))
    }

    @Test
    fun `the grace boundary is exclusive`() {
        assertFalse(credible(decoded = false, pendingAgeMs = PENDING_KEYFRAME_GRACE_MS))
    }

    @Test
    fun `the bundled software decoder keeps no keyframe accounting so a rendered frame is the only evidence`() {
        assertTrue(credible(accounting = false, decoded = false, pendingAgeMs = Long.MAX_VALUE))
    }

    @Test
    fun `the grace outlasts the tracker's own timestamp disbelief`() {
        // KeyframeRepairTracker gives up on timestamps after this many renders and confirms by
        // count instead. At the lowest plausible rate that is the wait below, and calling a stream
        // incredible before the tracker has had its own chance to confirm would fire on healthy
        // output from a component whose timestamps cannot be read.
        val slowestConfirmMs = KeyframeRepairTracker.RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED * 1000L / 30L
        assertTrue(PENDING_KEYFRAME_GRACE_MS >= slowestConfirmMs)
    }

    @Test
    fun `the answer settles long before the starvation path`() {
        assertTrue(PENDING_KEYFRAME_GRACE_MS < DecoderStallCausePolicy.KEYFRAME_STARVATION_PATIENCE_MS)
    }

    private fun credible(
        rendered: Boolean = true,
        accounting: Boolean = true,
        decoded: Boolean = false,
        pendingAgeMs: Long,
    ): Boolean = PictureCredibilityPolicy.hasCrediblePicture(
        renderedSinceCodecStart = rendered,
        keyframeAccountingAvailable = accounting,
        keyframeDecodedSinceCodecStart = decoded,
        msSincePendingKeyframeFed = pendingAgeMs,
    )
}
