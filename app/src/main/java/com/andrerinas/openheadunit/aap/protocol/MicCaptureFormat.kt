package com.andrerinas.openheadunit.aap.protocol

/**
 * The one microphone format Android Auto is told about, and the only one it is ever sent.
 *
 * Google's head unit guide requires PCM 16 kHz 16-bit mono for speech, and Android Auto validates
 * the announced configuration: anything outside {16000, 48000} Hz, 16 bits and 1 or 2 channels is
 * rejected, and the teardown reasons include a bad microphone config. The announcement and the
 * capture used to be two separate literals, which is how a head unit came to declare 16 kHz and
 * send 48.
 */
object MicCaptureFormat {

    const val SAMPLE_RATE_HZ = 16000

    const val BITS = 16

    const val CHANNELS = 1

    /** Bytes in one mono 16-bit sample. */
    const val FRAME_BYTES = 2

    /** What one second of this format weighs, which is what the uplink report is measured against. */
    const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * FRAME_BYTES

    /**
     * Frames in one message, which Google's head unit guide names and requires messages to be a
     * multiple of. Android Auto's own audio classes use the same number.
     */
    const val CHUNK_FRAMES = 2048

    /** [CHUNK_FRAMES] as bytes. 128 ms of audio at the announced rate. */
    const val CHUNK_BYTES = CHUNK_FRAMES * FRAME_BYTES

    /**
     * The only rate worth falling back to when the hardware refuses 16 kHz.
     *
     * Every Android device supports it, Android Auto accepts it, and three-to-one is a whole-group
     * decimation with no filter to design. A non-integer ratio would need a low-pass nobody here
     * can measure.
     */
    const val FALLBACK_SAMPLE_RATE_HZ = 48000
}
