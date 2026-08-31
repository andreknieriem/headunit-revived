package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.aap.protocol.MicCaptureFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which rate the microphone opens at, given that only 16 kHz ever reaches the phone. */
class MicCaptureRatePolicyTest {

    /** A device that supports exactly the rates listed. */
    private fun device(vararg supported: Int): (Int) -> Int =
        { rate -> if (rate in supported) rate / 8 else -2 }

    @Test
    fun `the announced rate is preferred and needs no conversion`() {
        val decision = MicCaptureRatePolicy.decide(48000, device(16000, 44100, 48000))!!
        assertEquals(MicCaptureFormat.SAMPLE_RATE_HZ, decision.captureRateHz)
        assertTrue(decision.isDirect)
        assertEquals(1, decision.decimationFactor)
    }

    @Test
    fun `a device that refuses 16 kHz falls back and converts three to one`() {
        val decision = MicCaptureRatePolicy.decide(48000, device(48000))!!
        assertEquals(48000, decision.captureRateHz)
        assertEquals(3, decision.decimationFactor)
        assertEquals(48000 / 8, decision.minBufferSize)
    }

    @Test
    fun `the fallback is taken even when the setting names something else`() {
        // The setting is a preference, not a licence: only whole multiples of 16 kHz are usable.
        val decision = MicCaptureRatePolicy.decide(16000, device(48000))!!
        assertEquals(48000, decision.captureRateHz)
    }

    @Test
    fun `a rate that is not a whole multiple is never chosen`() {
        // 44100 to 16000 would need a low-pass filter, which is why the picker no longer offers it.
        assertNull(MicCaptureRatePolicy.decide(44100, device(44100)))
    }

    @Test
    fun `a rate below the announced one is never chosen`() {
        // 8000 was in the old picker and could only ever have sent half-speed audio.
        assertNull(MicCaptureRatePolicy.decide(8000, device(8000)))
    }

    @Test
    fun `a device that opens nothing usable has no microphone`() {
        assertNull(MicCaptureRatePolicy.decide(48000, device()))
    }

    @Test
    fun `the announced rate is probed before anything else`() {
        val probed = mutableListOf<Int>()
        MicCaptureRatePolicy.decide(48000) { rate -> probed.add(rate); rate / 8 }
        assertEquals(listOf(MicCaptureFormat.SAMPLE_RATE_HZ), probed)
    }
}
