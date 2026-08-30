package com.andrerinas.openheadunit.decoder.audio

/**
 * Whole-group averaging from a multiple of 16 kHz down to 16 kHz, on mono 16-bit little-endian PCM.
 *
 * Only reached when the device refuses to open 16 kHz. Averaging rather than picking every Nth
 * sample because dropping samples folds everything above 8 kHz back into the band speech
 * recognition listens to; an N-sample mean is a crude low-pass that costs one add per sample.
 *
 * Stateful on purpose: a read is not a whole number of groups, so the tail of one read has to be
 * carried into the next or the stream loses a sample at every boundary.
 */
class MicPcmDecimator(private val factor: Int) {

    init {
        require(factor >= 1) { "factor must be at least 1, was $factor" }
    }

    private var pendingSum = 0
    private var pendingCount = 0

    /** Upper bound on the bytes [decimate] can produce from [inputBytes], for sizing a buffer. */
    fun outputCapacity(inputBytes: Int): Int = (inputBytes / 2 + pendingCount) / factor * 2 + 2

    /**
     * Reads [srcLen] bytes from [src] and writes the converted samples to [dst], returning how many
     * bytes it wrote. An odd trailing byte is ignored: it is half a sample and the next read starts
     * a new one.
     */
    fun decimate(src: ByteArray, srcLen: Int, dst: ByteArray): Int {
        var out = 0
        var i = 0
        while (i + 1 < srcLen) {
            val sample = ((src[i + 1].toInt() shl 8) or (src[i].toInt() and 0xFF)).toShort().toInt()
            pendingSum += sample
            pendingCount++
            if (pendingCount == factor) {
                val averaged = pendingSum / factor
                dst[out] = (averaged and 0xFF).toByte()
                dst[out + 1] = ((averaged shr 8) and 0xFF).toByte()
                out += 2
                pendingSum = 0
                pendingCount = 0
            }
            i += 2
        }
        return out
    }

    /** Drop the partial group. Called when capture stops, so the next session starts aligned. */
    fun reset() {
        pendingSum = 0
        pendingCount = 0
    }
}
