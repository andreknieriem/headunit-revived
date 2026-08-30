package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** The 48 kHz fallback's conversion, on mono 16-bit little-endian PCM. */
class MicPcmDecimatorTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samples(buf: ByteArray, len: Int): List<Int> =
        (0 until len step 2).map {
            ((buf[it + 1].toInt() shl 8) or (buf[it].toInt() and 0xFF)).toShort().toInt()
        }

    @Test
    fun `three samples become their mean`() {
        val decimator = MicPcmDecimator(3)
        val src = pcm(30, 60, 90, -30, -60, -90)
        val dst = ByteArray(decimator.outputCapacity(src.size))
        val len = decimator.decimate(src, src.size, dst)
        assertEquals(listOf(60, -60), samples(dst, len))
    }

    @Test
    fun `a partial group is carried into the next read`() {
        // The reason this is stateful: a read is not a whole number of groups, and dropping the
        // tail would lose a sample at every read boundary.
        val decimator = MicPcmDecimator(3)
        val dst = ByteArray(64)

        val first = pcm(30, 60)
        assertEquals(0, decimator.decimate(first, first.size, dst))

        val second = pcm(90, 300)
        val len = decimator.decimate(second, second.size, dst)
        assertEquals(listOf(60), samples(dst, len))
    }

    @Test
    fun `the whole stream is preserved across many reads`() {
        val decimator = MicPcmDecimator(3)
        val dst = ByteArray(4096)
        val produced = mutableListOf<Int>()
        // 300 samples fed in reads of 7, which never aligns with a group of 3.
        var next = 0
        while (next < 300) {
            val count = minOf(7, 300 - next)
            val src = pcm(*IntArray(count) { 3 }) // every sample the same, so every mean is 3
            next += count
            val len = decimator.decimate(src, src.size, dst)
            produced.addAll(samples(dst, len))
        }
        assertEquals(100, produced.size)
        assertEquals(List(100) { 3 }, produced)
    }

    @Test
    fun `an odd trailing byte is not read as a sample`() {
        val decimator = MicPcmDecimator(3)
        val src = pcm(10, 20, 30) + byteArrayOf(0x7f)
        val dst = ByteArray(32)
        val len = decimator.decimate(src, src.size, dst)
        assertEquals(listOf(20), samples(dst, len))
    }

    @Test
    fun `reset drops the partial group so the next session starts aligned`() {
        val decimator = MicPcmDecimator(3)
        val dst = ByteArray(32)
        val leading = pcm(1000, 1000)
        decimator.decimate(leading, leading.size, dst)
        decimator.reset()

        val src = pcm(30, 60, 90)
        val len = decimator.decimate(src, src.size, dst)
        assertEquals(listOf(60), samples(dst, len))
    }

    @Test
    fun `a factor of one passes every sample through`() {
        val decimator = MicPcmDecimator(1)
        val src = pcm(1, -1, 32767, -32768)
        val dst = ByteArray(decimator.outputCapacity(src.size))
        val len = decimator.decimate(src, src.size, dst)
        assertEquals(listOf(1, -1, 32767, -32768), samples(dst, len))
    }

    @Test
    fun `the output buffer is large enough for the largest read`() {
        val decimator = MicPcmDecimator(3)
        val src = pcm(*IntArray(1536) { 100 })
        val dst = ByteArray(decimator.outputCapacity(src.size))
        val len = decimator.decimate(src, src.size, dst)
        assertEquals(1536 / 3 * 2, len)
    }
}
