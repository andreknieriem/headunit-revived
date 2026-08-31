package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.aap.protocol.MicCaptureFormat

/**
 * Which rate to open the microphone at, given that only 16 kHz ever reaches the phone.
 *
 * 16 kHz first, because then there is nothing to convert. A device whose AudioRecord will not open
 * it is the only reason the rate setting still exists, and the only fallback worth taking is a whole
 * multiple of 16 kHz so the conversion is an average over whole sample groups.
 *
 * Pure: the caller supplies the probe, so every branch is reachable in a test.
 */
object MicCaptureRatePolicy {

    /**
     * @param preferredFallbackHz what the user's compatibility setting asks for if 16 kHz fails.
     * @param minBufferSize AudioRecord.getMinBufferSize for a rate, mono 16-bit; zero or less means
     *   the device cannot open that configuration.
     */
    fun decide(preferredFallbackHz: Int, minBufferSize: (Int) -> Int): Decision? {
        val direct = minBufferSize(MicCaptureFormat.SAMPLE_RATE_HZ)
        if (direct > 0) return Decision(MicCaptureFormat.SAMPLE_RATE_HZ, direct)

        val candidates = linkedSetOf(preferredFallbackHz, MicCaptureFormat.FALLBACK_SAMPLE_RATE_HZ)
        for (rate in candidates) {
            if (rate <= MicCaptureFormat.SAMPLE_RATE_HZ) continue
            if (rate % MicCaptureFormat.SAMPLE_RATE_HZ != 0) continue
            val size = minBufferSize(rate)
            if (size > 0) return Decision(rate, size)
        }
        return null
    }

    /** A rate the device will open, and the buffer it needs. */
    data class Decision(val captureRateHz: Int, val minBufferSize: Int) {

        /** How many captured samples make one sample on the wire. One means send them as they are. */
        val decimationFactor: Int get() = captureRateHz / MicCaptureFormat.SAMPLE_RATE_HZ

        val isDirect: Boolean get() = decimationFactor == 1
    }
}
