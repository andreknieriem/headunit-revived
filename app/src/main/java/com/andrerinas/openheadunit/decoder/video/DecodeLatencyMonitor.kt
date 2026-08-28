package com.andrerinas.openheadunit.decoder.video

/**
 * How long the component holds a frame between being handed it and giving it back.
 *
 * This is the one number that says whether a low-latency key did anything. A configure that does not
 * throw proves only that the key cost nothing, and the format cannot be read back for an answer -
 * ACodec reports its own bookkeeping rather than re-asking the component, so an ignored key
 * round-trips exactly like an honoured one. What is left is the behaviour, and the behaviour those
 * keys claim to change is precisely this delay.
 *
 * It costs one subtraction per frame because the timestamps are already there: input buffers carry
 * the frame's arrival time as their presentation stamp, and the component hands the same stamp back
 * on output. Nothing new is threaded through and no side table is kept.
 *
 * Pure and clock-free - the caller supplies every number - so a measured session replays in a test.
 * Not thread-safe: the output thread is the only writer and the only reader.
 */
class DecodeLatencyMonitor {

    private val samples = LongArray(CAPACITY)
    private var count = 0
    private var next = 0
    private var implausible = 0

    /**
     * One frame came out [latencyUs] after it went in.
     *
     * Values outside [MAX_PLAUSIBLE_US] are counted and discarded rather than averaged in. A
     * component that does not carry timestamps through hands back zero or a constant, and the
     * resulting figure would be a number rather than a measurement - worse than no line at all,
     * because a number gets quoted.
     */
    fun onFrameDecoded(latencyUs: Long) {
        if (latencyUs < 0 || latencyUs > MAX_PLAUSIBLE_US) {
            implausible++
            return
        }
        samples[next] = latencyUs
        next = (next + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    /**
     * The window's figures, or null when nothing usable arrived and nothing was rejected either -
     * which is simply a window with no frames in it, and has no line to contribute.
     *
     * Clears the samples, so each report covers only the window since the last one.
     */
    fun report(): Report? {
        if (count == 0 && implausible == 0) return null
        val sorted = samples.copyOf(count).also { it.sort() }
        val report = Report(
            medianUs = percentile(sorted, 50),
            p95Us = percentile(sorted, 95),
            frames = count,
            unusable = implausible,
        )
        count = 0
        next = 0
        implausible = 0
        return report
    }

    fun reset() {
        count = 0
        next = 0
        implausible = 0
    }

    /**
     * [unusable] is the count this window discarded. It is reported rather than hidden because a
     * window that is all discards is a finding about the component, not an absence of data.
     */
    data class Report(
        val medianUs: Long,
        val p95Us: Long,
        val frames: Int,
        val unusable: Int,
    ) {
        override fun toString(): String {
            if (frames == 0) return "decodeLatency=unreadable ($unusable frames)"
            val suffix = if (unusable == 0) "" else ", $unusable unreadable"
            return "decodeLatency=${medianUs / 1000}ms p95=${p95Us / 1000}ms " +
                "($frames frames$suffix)"
        }
    }

    companion object {
        /**
         * A little over ten seconds at 60fps. Large enough that a window is never summarised from a
         * handful of frames, small enough to stay a rounding error against the frame buffers.
         */
        const val CAPACITY = 640

        /** Beyond one second the stamp is not a decode delay, whatever else it is. */
        const val MAX_PLAUSIBLE_US = 1_000_000L

        /** Nearest-rank, so a single sample is its own median and its own p95. */
        private fun percentile(sorted: LongArray, percentile: Int): Long {
            if (sorted.isEmpty()) return 0
            val rank = (sorted.size * percentile + 99) / 100
            return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
        }
    }
}
