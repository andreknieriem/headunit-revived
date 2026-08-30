package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.MsgType
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record of the microphone frame's layout, and of the one rule it kept breaking: flags 0x0b
 * promises a 2-byte message type and the uplink never wrote one.
 */
class MicUplinkFrameTest {

    private val pcm = byteArrayOf(
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x78.toByte()
    )

    private fun build(timestampUs: Long = 1L, data: ByteArray = pcm): Pair<ByteArray, Int> {
        val into = ByteArray(MicUplinkFrame.size(data.size))
        val length = MicUplinkFrame.build(timestampUs, data, 0, data.size, into)
        return into to length
    }

    @Test
    fun `a frame is the payload plus fourteen bytes`() {
        // Two more than dc039351 wrote and four more than 42fcd389: both omitted the message type.
        assertEquals(14, MicUplinkFrame.size(0))
        assertEquals(4110, MicUplinkFrame.size(4096))
        assertEquals(MicUplinkFrame.size(pcm.size), build().second)
    }

    @Test
    fun `the offsets agree with the message the transport builds around them`() {
        assertEquals(AapMessage.HEADER_SIZE, MicUplinkFrame.HEADER_SIZE)
        assertEquals(MsgType.SIZE, MicUplinkFrame.TYPE_SIZE)
        assertEquals(AapMessage.HEADER_SIZE + MsgType.SIZE, MicUplinkFrame.TIMESTAMP_OFFSET)
    }

    @Test
    fun `the header names the channel and promises a message type`() {
        val (frame, _) = build()
        assertEquals(Channel.ID_MIC.toByte(), frame[0])
        assertEquals(0x0b.toByte(), frame[1])
        // The whole bug in one assertion: this flag byte is what obliges the frame to carry a type.
        assertTrue(AapMessageFraming.carriesMessageType(frame[1].toInt()))
    }

    @Test
    fun `the length field is left for the encrypt step`() {
        // sendEncryptedMessage writes the ciphertext length here; anything we put would be lost.
        val (frame, _) = build()
        assertEquals(0, frame[2].toInt())
        assertEquals(0, frame[3].toInt())
    }

    @Test
    fun `the message type is the first thing the phone decrypts`() {
        val (frame, _) = build()
        val type = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_DATA_VALUE, type)
    }

    @Test
    fun `the timestamp is eight big-endian bytes and survives a non-zero top byte`() {
        // The regression guard. Both historical layouts shifted these bytes, which went unnoticed
        // because a millisecond elapsedRealtime never fills the top two.
        val (frame, _) = build(timestampUs = 0x0102030405060708L)
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            frame.copyOfRange(MicUplinkFrame.TIMESTAMP_OFFSET, MicUplinkFrame.PCM_OFFSET)
        )
    }

    @Test
    fun `every PCM byte survives, including the first sample`() {
        // dc039351 fed the phone's timestamp read the first sample; 42fcd389 fed it the first two.
        val (frame, length) = build()
        assertArrayEquals(pcm, frame.copyOfRange(MicUplinkFrame.PCM_OFFSET, length))
    }

    @Test
    fun `a slice of a reused capture buffer is copied, not the whole buffer`() {
        val reused = byteArrayOf(-1, -1, 0x0a, 0x0b, 0x0c, 0x0d, -1, -1)
        val into = ByteArray(MicUplinkFrame.size(4))
        val length = MicUplinkFrame.build(1L, reused, 2, 4, into)
        assertArrayEquals(
            byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d),
            into.copyOfRange(MicUplinkFrame.PCM_OFFSET, length)
        )
    }

    @Test
    fun `the phone reads it back with the rules this app uses inbound`() {
        // The tie between the sender and the receiver we already trust. AapMessageIncoming takes
        // the type from payload offset 0 and AapAudio.process takes media data from offset 10.
        val (frame, length) = build(timestampUs = 987_654_321L)
        val payload = frame.copyOfRange(MicUplinkFrame.HEADER_SIZE, length)

        // Decoded here rather than through Utils.bytesToInt, which drags AppLog and android.util.Log
        // into a JVM test; the arithmetic is that method's, two bytes big-endian.
        val type = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        assertEquals(Media.MsgType.MEDIA_MESSAGE_DATA_VALUE, type)
        assertArrayEquals(pcm, payload.copyOfRange(10, payload.size))
    }
}
