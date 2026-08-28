package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundRateMonitorTest {

    private val window = InboundRateMonitor.WINDOW_MS

    /** Feeds [count] messages of [bytes] evenly across [spanMs], returning the reports produced. */
    private fun feed(
        monitor: InboundRateMonitor,
        channel: Int,
        bytes: Int,
        count: Int,
        startMs: Long,
        spanMs: Long,
    ): List<InboundRateMonitor.Report> {
        val reports = mutableListOf<InboundRateMonitor.Report>()
        for (i in 0 until count) {
            val at = startMs + spanMs * i / count
            monitor.onMessage(channel, bytes, at)?.let { reports.add(it) }
        }
        return reports
    }

    @Test
    fun `nothing is reported before a window closes`() {
        val monitor = InboundRateMonitor()
        assertNull(monitor.onMessage(Channel.ID_VID, 8192, 0L))
        assertNull(monitor.onMessage(Channel.ID_VID, 8192, window - 1))
    }

    @Test
    fun `a window closes on the first message at or past the bound`() {
        val monitor = InboundRateMonitor()
        monitor.onMessage(Channel.ID_VID, 8192, 0L)
        assertNotNull(monitor.onMessage(Channel.ID_VID, 8192, window))
    }

    @Test
    fun `the channels are counted apart`() {
        val monitor = InboundRateMonitor()
        monitor.onMessage(Channel.ID_VID, 1000, 0L)
        monitor.onMessage(Channel.ID_AUD, 100, 1L)
        monitor.onMessage(Channel.ID_AU1, 100, 2L)
        monitor.onMessage(Channel.ID_CTR, 10, 3L)
        val report = monitor.onMessage(Channel.ID_VID, 1000, window)!!
        assertEquals(2000L, report.videoBytes)
        assertEquals(200L, report.audioBytes)
        assertEquals(10L, report.otherBytes)
        assertEquals(2, report.videoMessages)
        assertEquals(2, report.audioMessages)
        assertEquals(1, report.otherMessages)
    }

    @Test
    fun `a window starts empty, so two windows do not accumulate`() {
        val monitor = InboundRateMonitor()
        monitor.onMessage(Channel.ID_VID, 1000, 0L)
        monitor.onMessage(Channel.ID_VID, 1000, window)
        val second = monitor.onMessage(Channel.ID_VID, 1000, window * 2)!!
        assertEquals("the first window's bytes must not be carried over", 1000L, second.videoBytes)
    }

    @Test
    fun `a reset makes the next message the start of a new session`() {
        val monitor = InboundRateMonitor()
        monitor.onMessage(Channel.ID_VID, 1000, 0L)
        monitor.reset()
        // Without the reset this would close a window immediately, on a stamp the previous phone
        // left behind.
        assertNull(monitor.onMessage(Channel.ID_VID, 1000, window))
    }

    @Test
    fun `a negative size cannot run the totals backwards`() {
        // The size field comes off the wire, so it is not this object's job to trust it.
        val monitor = InboundRateMonitor()
        monitor.onMessage(Channel.ID_VID, -5000, 0L)
        val report = monitor.onMessage(Channel.ID_VID, 1000, window)!!
        assertEquals(1000L, report.videoBytes)
        assertEquals("the message is still counted, only its size is not", 2, report.videoMessages)
    }

    @Test
    fun `the rate is whole kB per second of the window actually measured`() {
        val monitor = InboundRateMonitor()
        // Thirty 1 KiB messages across a 30s window is 1 kB/s. Twenty-nine of them land inside the
        // window and the thirtieth closes it, and the closing message is counted in the window it
        // closes.
        monitor.onMessage(Channel.ID_VID, 1024, 0L)
        assertTrue(feed(monitor, Channel.ID_VID, 1024, 28, 1L, window - 2).isEmpty())
        val report = monitor.onMessage(Channel.ID_VID, 1024, window)!!
        assertEquals(30, report.videoMessages)
        assertEquals(1L, report.kbPerSecond(report.videoBytes))
        assertEquals(0L, report.kbPerSecond(0L))
    }

    /**
     * The shape the instrument exists to make visible, replayed from a reporter's driving logs: the
     * picture falls to a tenth of its rate while 48 kHz stereo PCM audio holds its own across the
     * same seconds. A link at its ceiling cannot do that, so the phone was choosing to send less.
     */
    @Test
    fun `a video sag beside untouched audio is readable from two consecutive lines`() {
        val monitor = InboundRateMonitor()
        // Audio is the constant: 48kHz stereo 16-bit PCM is about 192 kB/s, delivered in ~20ms
        // chunks, and it never faltered in either capture.
        val audioBytesPerWindow = 192 * 1024 * (window / 1000).toInt()
        val audioMsgs = 1500
        val audioChunk = audioBytesPerWindow / audioMsgs

        monitor.onMessage(Channel.ID_VID, 8192, 0L)
        feed(monitor, Channel.ID_AUD, audioChunk, audioMsgs, 1L, window - 2)
        feed(monitor, Channel.ID_VID, 8192, 900, 1L, window - 2)
        val healthy = monitor.onMessage(Channel.ID_VID, 8192, window)!!

        feed(monitor, Channel.ID_AUD, audioChunk, audioMsgs, window + 1, window - 2)
        feed(monitor, Channel.ID_VID, 8192, 90, window + 1, window - 2)
        val sagging = monitor.onMessage(Channel.ID_VID, 8192, window * 2)!!

        assertTrue(
            "video should fall by about a factor of ten",
            sagging.kbPerSecond(sagging.videoBytes) * 5 < healthy.kbPerSecond(healthy.videoBytes)
        )
        assertEquals(
            "audio must be unchanged - that is the whole finding",
            healthy.kbPerSecond(healthy.audioBytes),
            sagging.kbPerSecond(sagging.audioBytes)
        )
    }

    @Test
    fun `the line names every channel and its window`() {
        val monitor = InboundRateMonitor()
        monitor.onMessage(Channel.ID_VID, 1000, 0L)
        val line = monitor.onMessage(Channel.ID_AUD, 1000, window)!!.toString()
        assertTrue(line, line.startsWith("inbound rate over ${window}ms:"))
        assertTrue(line, line.contains("video="))
        assertTrue(line, line.contains("audio="))
        assertTrue(line, line.contains("other="))
    }
}
