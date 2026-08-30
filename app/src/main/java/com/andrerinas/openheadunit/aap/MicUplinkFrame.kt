package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/**
 * The bytes of one microphone message, and the one sender that has to obey [AapMessageFraming].
 *
 * That table says a message with flag bit 0 set carries a 2-byte type at payload offset 0, and the
 * receive path reads exactly that: [AapMessageIncoming] takes the type from payload offset 0 and
 * [AapAudio.process] takes media data from offset 10, which is 2 of type plus 8 of timestamp. The
 * uplink set the flags and never wrote the type, so the phone read six timestamp bytes plus the
 * first PCM sample as the timestamp and started the audio one sample late, on every message. It
 * parsed at all only because a millisecond `elapsedRealtime()` has two leading zero bytes.
 *
 * Pure: no Android, no logging. Its test is the record of the layout below.
 */
object MicUplinkFrame {

    /** Channel, flags and the 2-byte length the encrypt step rewrites. Plaintext on the wire. */
    const val HEADER_SIZE = 4

    /** First byte the phone decrypts. [AapTransport.sendEncryptedMessage] encrypts from here. */
    const val TYPE_OFFSET = HEADER_SIZE

    const val TYPE_SIZE = 2

    const val TIMESTAMP_OFFSET = TYPE_OFFSET + TYPE_SIZE

    const val TIMESTAMP_SIZE = 8

    const val PCM_OFFSET = TIMESTAMP_OFFSET + TIMESTAMP_SIZE

    /** Complete, unfragmented, encrypted. Bit 0 is what obliges us to write a type. */
    const val FLAGS: Byte = 0x0b

    fun size(pcmLength: Int): Int = PCM_OFFSET + pcmLength

    /**
     * Writes one media message into [into] and returns its length.
     *
     * [timestampUs] is microseconds, matching every other AAP media producer. The caller owns the
     * clock so a session can be replayed in a test.
     */
    fun build(
        timestampUs: Long,
        pcm: ByteArray,
        pcmOffset: Int,
        pcmLength: Int,
        into: ByteArray
    ): Int {
        val length = size(pcmLength)
        require(into.size >= length) { "buffer holds ${into.size}, frame needs $length" }

        into[0] = Channel.ID_MIC.toByte()
        into[1] = FLAGS
        // [2..3] is the ciphertext length, written by sendEncryptedMessage once the payload exists.
        into[2] = 0
        into[3] = 0

        val type = Media.MsgType.MEDIA_MESSAGE_DATA_VALUE
        into[TYPE_OFFSET] = (type shr 8).toByte()
        into[TYPE_OFFSET + 1] = (type and 0xFF).toByte()

        var remaining = timestampUs
        for (i in TIMESTAMP_SIZE - 1 downTo 0) {
            into[TIMESTAMP_OFFSET + i] = (remaining and 0xFF).toByte()
            remaining = remaining shr 8
        }

        System.arraycopy(pcm, pcmOffset, into, PCM_OFFSET, pcmLength)
        return length
    }
}
