package com.andrerinas.openheadunit.decoder.audio

/**
 * How much audio to bank in an [android.media.AudioTrack] before starting playback on it.
 *
 * The track was started the moment it was built, on an empty buffer, and all five media-stream
 * starts in a measured capture underran within a second. Audio arrives at real time, so the writer
 * never fills a buffer faster than the mixer drains it and the opening deficit is never recovered -
 * which is also why a deep [com.andrerinas.openheadunit.utils.Settings.audioLatencyMultiplier]
 * bought so little.
 *
 * Bank frames first and play once there are enough, as [AudioMixer] already does for its channels.
 *
 * Pure and clock-free: the caller passes the elapsed time in.
 */
object AudioPrerollPolicy {

    /**
     * How much audio to bank, in milliseconds of playback.
     *
     * A ceiling in time, not a share of the buffer: the default buffer is eight times the device
     * minimum, and filling it would put two thirds of a second between pressing play and hearing
     * anything. Above the ~87 ms device minimum, below the ~250 ms where a resume feels detached.
     */
    const val TARGET_MS = 200L

    /**
     * Never bank more than this share of the track's own buffer.
     *
     * `write()` on a track that is not playing blocks until only `play()` can make room, and
     * `play()` is called from the writing thread - so a target the next chunk cannot fit under is a
     * deadlock, not a delay. The margin leaves that chunk room.
     */
    const val MAX_FILL_NUMERATOR = 3
    const val MAX_FILL_DENOMINATOR = 4

    /**
     * Start anyway once this long has passed with anything at all banked.
     *
     * A notification blip can be shorter than [TARGET_MS], and waiting for a target it will never
     * reach would silence it. Set beyond [TARGET_MS] so an ordinary stream still starts on fill.
     */
    const val MAX_WAIT_MS = 300L

    /**
     * Frames to bank before starting, for a track of [bufferCapacityFrames] at [sampleRateInHz].
     *
     * At least one, so a nonsense capacity still yields a track that plays.
     */
    fun targetFrames(sampleRateInHz: Int, bufferCapacityFrames: Int): Int {
        if (sampleRateInHz <= 0) return 1
        val byTime = (sampleRateInHz.toLong() * TARGET_MS / 1000L).toInt()
        val byBuffer = bufferCapacityFrames / MAX_FILL_DENOMINATOR * MAX_FILL_NUMERATOR
        val capped = if (bufferCapacityFrames > 0) minOf(byTime, byBuffer) else byTime
        return capped.coerceAtLeast(1)
    }

    /**
     * Whether the track should be started now.
     *
     * @param framesBanked frames already written to the track.
     * @param framesIncoming frames about to be written, counted here so the decision is made before
     *   the write rather than after it. See [MAX_FILL_NUMERATOR].
     * @param targetFrames from [targetFrames].
     * @param elapsedMs since the track was built.
     */
    fun shouldStart(
        framesBanked: Long,
        framesIncoming: Int,
        targetFrames: Int,
        elapsedMs: Long
    ): Boolean {
        val total = framesBanked + framesIncoming
        if (total >= targetFrames) return true
        // Nothing banked is a stream that has not begun, not a short one. Starting on it would be
        // the empty-buffer start again, with a timer in front of it.
        return total > 0 && elapsedMs >= MAX_WAIT_MS
    }
}
