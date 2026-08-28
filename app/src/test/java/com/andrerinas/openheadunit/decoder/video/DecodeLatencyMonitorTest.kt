package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeLatencyMonitorTest {

    private val monitor = DecodeLatencyMonitor()

    @Test
    fun `a window with no frames has nothing to say`() {
        assertNull(monitor.report())
    }

    @Test
    fun `one frame is its own median and its own p95`() {
        monitor.onFrameDecoded(12_000)
        val report = monitor.report()!!
        assertEquals(12_000L, report.medianUs)
        assertEquals(12_000L, report.p95Us)
        assertEquals(1, report.frames)
    }

    @Test
    fun `the tail is what the p95 reports, not the average`() {
        // Ninety frames at 10ms and ten at 500ms: a mean would read 59ms, which describes neither
        // the frames that were fine nor the ones that were not. That is why this reports
        // percentiles.
        repeat(90) { monitor.onFrameDecoded(10_000) }
        repeat(10) { monitor.onFrameDecoded(500_000) }
        val report = monitor.report()!!
        assertEquals(10_000L, report.medianUs)
        assertEquals(500_000L, report.p95Us)
        assertEquals(100, report.frames)
    }

    @Test
    fun `a component that does not carry timestamps through is named, not averaged`() {
        // The failure this exists to survive: the stamp comes back as zero, so the subtraction gives
        // the whole session's elapsed time and every sample is nonsense.
        repeat(20) { monitor.onFrameDecoded(45_000_000) }
        val report = monitor.report()!!
        assertEquals(0, report.frames)
        assertEquals(20, report.unusable)
        assertTrue(report.toString().contains("unreadable"))
    }

    @Test
    fun `a negative stamp is discarded rather than counted as instant`() {
        monitor.onFrameDecoded(-1)
        monitor.onFrameDecoded(8_000)
        val report = monitor.report()!!
        assertEquals(1, report.frames)
        assertEquals(1, report.unusable)
        assertEquals(8_000L, report.medianUs)
    }

    @Test
    fun `each report covers only the window since the last one`() {
        monitor.onFrameDecoded(80_000)
        monitor.report()
        monitor.onFrameDecoded(4_000)
        val second = monitor.report()!!
        assertEquals("the first window's samples must not carry over", 4_000L, second.medianUs)
        assertEquals(1, second.frames)
        assertNull(monitor.report())
    }

    @Test
    fun `a long window keeps the newest samples rather than growing without bound`() {
        // Slow frames first, then a full buffer of fast ones. The ring must have discarded the slow
        // ones, or a single busy window would colour every report after it.
        repeat(100) { monitor.onFrameDecoded(900_000) }
        repeat(DecodeLatencyMonitor.CAPACITY) { monitor.onFrameDecoded(5_000) }
        val report = monitor.report()!!
        assertEquals(DecodeLatencyMonitor.CAPACITY, report.frames)
        assertEquals(5_000L, report.medianUs)
        assertEquals(5_000L, report.p95Us)
    }

    @Test
    fun `reset drops the window without reporting it`() {
        monitor.onFrameDecoded(9_000)
        monitor.reset()
        assertNull(monitor.report())
    }

    @Test
    fun `the line reads in milliseconds, because that is the scale being compared`() {
        monitor.onFrameDecoded(16_400)
        assertTrue(monitor.report()!!.toString().startsWith("decodeLatency=16ms p95=16ms (1 frames)"))
    }
}
