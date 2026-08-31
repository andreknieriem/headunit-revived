package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A replayed microphone session, which is the only way to measure one without a head unit. */
class MicUplinkMonitorTest {

    private val monitor = MicUplinkMonitor()

    /** Feed [seconds] of audio at exactly the announced rate, in 4096-byte frames. */
    private fun feedNominal(seconds: Int, peak: Int = 1000) {
        val framesPerSecond = MicUplinkMonitor.BYTES_PER_SECOND / 4096.0
        val frames = (framesPerSecond * seconds).toInt()
        val stepMs = (1000L * seconds) / frames
        for (i in 0 until frames) monitor.onFrame(4096, peak, i * stepMs)
    }

    @Test
    fun `a session with no frames says nothing`() {
        // Which is itself the finding: the phone asked for the microphone and nothing left.
        assertFalse(monitor.isOpen)
        assertNull(monitor.onSessionEnd(1_000L))
    }

    @Test
    fun `the first frame opens the session and only the first`() {
        assertTrue(monitor.onFrame(1280, 500, 0L))
        assertFalse(monitor.onFrame(1280, 500, 40L))
        assertTrue(monitor.isOpen)
    }

    @Test
    fun `a session at the announced rate scores about a hundred percent`() {
        feedNominal(seconds = 4)
        val report = monitor.onSessionEnd(4_000L)!!
        assertTrue("was ${report.percentOfExpected}%", report.percentOfExpected in 95..105)
    }

    @Test
    fun `a session at half the byte rate scores about half`() {
        // 16000 bytes over one second against a 32000 bytes-per-second budget.
        for (i in 0 until 10) monitor.onFrame(1600, 900, i * 100L)
        val report = monitor.onSessionEnd(1_000L)!!
        assertTrue("was ${report.percentOfExpected}%", report.percentOfExpected in 45..55)
    }

    @Test
    fun `the report carries the loudest sample and the frame extremes`() {
        monitor.onFrame(1280, 12, 0L)
        monitor.onFrame(4096, 8214, 100L)
        monitor.onFrame(2048, 300, 200L)
        val report = monitor.onSessionEnd(300L)!!
        assertEquals(3, report.frames)
        assertEquals(1280L + 4096L + 2048L, report.bytes)
        assertEquals(8214, report.peak)
        assertEquals(4096, report.largest)
        assertEquals(1280, report.smallest)
    }

    @Test
    fun `a silent microphone reports a zero peak rather than nothing`() {
        feedNominal(seconds = 2, peak = 0)
        val report = monitor.onSessionEnd(2_000L)!!
        assertEquals(0, report.peak)
        assertTrue(report.frames > 0)
    }

    @Test
    fun `acks are only counted while a session is open`() {
        monitor.onAck()
        monitor.onFrame(4096, 100, 0L)
        monitor.onAck()
        monitor.onAck()
        assertEquals(2, monitor.onSessionEnd(200L)!!.acks)
    }

    @Test
    fun `a session that ends leaves nothing behind for the next one`() {
        // The transport outlives a session and is re-armed, so every field has to be restored.
        monitor.onFrame(4096, 9999, 0L)
        monitor.onSessionEnd(200L)
        assertFalse(monitor.isOpen)

        monitor.onFrame(1280, 5, 1_000L)
        val second = monitor.onSessionEnd(1_100L)!!
        assertEquals(1, second.frames)
        assertEquals(5, second.peak)
        assertEquals(100L, second.elapsedMs)
    }

    @Test
    fun `reset abandons an open session`() {
        monitor.onFrame(4096, 100, 0L)
        monitor.reset()
        assertFalse(monitor.isOpen)
        assertNull(monitor.onSessionEnd(500L))
    }

    @Test
    fun `a zero-length session reports an unknown percentage rather than dividing by zero`() {
        monitor.onFrame(4096, 100, 500L)
        assertEquals(-1, monitor.onSessionEnd(500L)!!.percentOfExpected)
    }
}
