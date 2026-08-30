package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.aap.protocol.MicCaptureFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driven by the read sizes reporter logs actually showed - 640 and 768 frames - because those are
 * the sizes that never lined up with the 2048 the protocol asks for.
 */
class MicChunkAccumulatorTest {

    private val accumulator = MicChunkAccumulator()

    private class Emitted(val bytes: ByteArray, val timestampUs: Long, val peak: Int)

    private val emitted = mutableListOf<Emitted>()

    private fun offer(src: ByteArray, capturedAtUs: Long = 0L, peak: Int = 0) {
        accumulator.offer(src, src.size, capturedAtUs, peak) { chunk, length, tsUs, chunkPeak ->
            emitted.add(Emitted(chunk.copyOf(length), tsUs, chunkPeak))
        }
    }

    private fun read(bytes: Int, fill: Byte = 7) = ByteArray(bytes) { fill }

    @Test
    fun `640-frame reads still produce whole messages`() {
        repeat(16) { offer(read(1280)) }
        assertEquals(5, emitted.size)
        assertTrue(emitted.all { it.bytes.size == MicCaptureFormat.CHUNK_BYTES })
        assertEquals(20480 - 5 * 4096, accumulator.residueBytes)
    }

    @Test
    fun `768-frame reads still produce whole messages`() {
        repeat(8) { offer(read(1536)) }
        assertEquals(3, emitted.size)
        assertTrue(emitted.all { it.bytes.size == MicCaptureFormat.CHUNK_BYTES })
        assertEquals(12288 - 3 * 4096, accumulator.residueBytes)
    }

    @Test
    fun `a read larger than a chunk emits more than one`() {
        offer(read(9000))
        assertEquals(2, emitted.size)
        assertEquals(9000 - 2 * 4096, accumulator.residueBytes)
    }

    @Test
    fun `a short read is held, not dropped`() {
        // The old sender discarded anything of 64 bytes or fewer outright, which made a short read
        // a hole in the audio rather than a wait.
        offer(read(48))
        assertEquals(0, emitted.size)
        assertEquals(48, accumulator.residueBytes)
    }

    @Test
    fun `nothing is lost and nothing is duplicated`() {
        // The assertion that says the drop is really gone: everything fed comes back out, in order.
        val fed = ByteArray(4096 * 3 + 700) { (it % 251).toByte() }
        var offset = 0
        while (offset < fed.size) {
            val len = minOf(1280, fed.size - offset)
            accumulator.offer(fed.copyOfRange(offset, offset + len), len, 0L, 0) { chunk, length, _, _ ->
                emitted.add(Emitted(chunk.copyOf(length), 0L, 0))
            }
            offset += len
        }
        val reassembled = emitted.fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        assertArrayEquals(fed.copyOfRange(0, reassembled.size), reassembled)
        assertEquals(fed.size, reassembled.size + accumulator.residueBytes)
    }

    @Test
    fun `a chunk is stamped when its first byte was captured, not when it completed`() {
        // A chunk is 128 ms long; stamping at the end would put every message that far out.
        offer(read(1280), capturedAtUs = 1_000_000L)
        offer(read(1280), capturedAtUs = 1_040_000L)
        offer(read(1280), capturedAtUs = 1_080_000L)
        offer(read(1280), capturedAtUs = 1_120_000L)
        assertEquals(1, emitted.size)
        assertEquals(1_000_000L, emitted[0].timestampUs)
    }

    @Test
    fun `a chunk starting mid-read is stamped from inside that read`() {
        offer(read(8192), capturedAtUs = 0L)
        assertEquals(2, emitted.size)
        assertEquals(0L, emitted[0].timestampUs)
        // 4096 bytes at 32000 bytes per second is 128 ms.
        assertEquals(128_000L, emitted[1].timestampUs)
    }

    @Test
    fun `a chunk carries the loudest read that fed it`() {
        offer(read(1280), peak = 10)
        offer(read(1280), peak = 9000)
        offer(read(1280), peak = 40)
        offer(read(1280), peak = 12)
        assertEquals(9000, emitted[0].peak)
    }

    @Test
    fun `the peak does not leak into the next chunk`() {
        offer(read(4096), peak = 30000)
        offer(read(4096), peak = 5)
        assertEquals(30000, emitted[0].peak)
        assertEquals(5, emitted[1].peak)
    }

    @Test
    fun `reset reports the tail it drops and starts clean`() {
        // Discarded, never padded: zero padding would inject silence the microphone never heard.
        offer(read(1280))
        assertEquals(1280, accumulator.reset())
        assertEquals(0, accumulator.residueBytes)
        assertEquals(0, accumulator.reset())

        offer(read(4096), capturedAtUs = 500L)
        assertEquals(1, emitted.size)
        assertEquals(500L, emitted[0].timestampUs)
    }
}
