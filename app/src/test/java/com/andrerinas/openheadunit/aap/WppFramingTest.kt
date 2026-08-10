package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WppFramingTest {

    @Test
    fun `header bytes are pinned, big-endian, for a hand-worked example`() {
        // 0x0102 = 258 bytes of payload, message type 3 (WifiInfoResponse).
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x00, 0x03),
            WppFraming.encodeHeader(payloadSize = 258, type = 3)
        )
    }

    @Test
    fun `the frame is the header followed by the payload unchanged`() {
        val payload = byteArrayOf(0x08, 0x00, 0x7F, -0x80)
        assertArrayEquals(
            byteArrayOf(0x00, 0x04, 0x00, 0x06) + payload,
            WppFraming.encodeFrame(payload, WppMessageType.CONNECT_STATUS)
        )
    }

    @Test
    fun `sizes round-trip through the header, including the ones that use the high byte`() {
        // 255/256 straddle the byte boundary that a naive encoder gets wrong; 990 is a plausible
        // real payload; 65535 is the largest the field can describe.
        for (size in listOf(0, 1, 255, 256, 990, WppFraming.MAX_PAYLOAD_SIZE)) {
            val header = WppFraming.encodeHeader(size, WppMessageType.START_REQUEST)
            assertEquals("size $size", size, WppFraming.decodePayloadSize(header))
            assertEquals("size $size", WppMessageType.START_REQUEST, WppFraming.decodeType(header))
        }
    }

    @Test
    fun `types round-trip through the header, including ones above one byte`() {
        for (type in listOf(0, 1, 11, 255, 256, 0xFFFF)) {
            val header = WppFraming.encodeHeader(payloadSize = 7, type = type)
            assertEquals("type $type", type, WppFraming.decodeType(header))
            assertEquals("type $type", 7, WppFraming.decodePayloadSize(header))
        }
    }

    @Test
    fun `decoding ignores whatever follows the header`() {
        val frame = WppFraming.encodeFrame(ByteArray(300) { 0x41 }, WppMessageType.INFO_RESPONSE)
        assertEquals(300, WppFraming.decodePayloadSize(frame))
        assertEquals(WppMessageType.INFO_RESPONSE, WppFraming.decodeType(frame))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a payload too large for the length field is refused, not truncated`() {
        // Silently wrapping the length would put the phone permanently out of sync with the
        // stream: every message after it is read at the wrong offset.
        WppFraming.encodeHeader(payloadSize = WppFraming.MAX_PAYLOAD_SIZE + 1, type = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative payload size is refused`() {
        WppFraming.encodeHeader(payloadSize = -1, type = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decoding refuses a short header rather than reading past it`() {
        WppFraming.decodePayloadSize(byteArrayOf(0x00, 0x04, 0x00))
    }
}
