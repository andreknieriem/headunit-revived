package com.andrerinas.openheadunit.aap

/**
 * The wire framing of the Android Auto WiFi Projection Protocol, as spoken over the RFCOMM channel
 * before the phone joins our network.
 *
 * Every message is four header bytes followed by the payload:
 *
 * ```
 *   0   1   2   3   4 ...
 * +---+---+---+---+--------------+
 * | payload len   |  message type | protobuf payload
 * |  uint16 BE    |   uint16 BE   |
 * +---+---+---+---+--------------+
 * ```
 *
 * Both fields are big-endian, byte-for-byte what aa-proxy-rs's dongle writes — the check that this
 * is the protocol and not one head unit's habits.
 *
 * Pure and tested because it is the one part of the exchange that can be wrong in a way no log
 * shows: a header we build by hand and the phone silently discards. Sizes above 255 exercise the
 * high byte; what we send stays well under it, what we receive is not ours to bound.
 */
object WppFraming {

    /** Bytes of header in front of every payload. */
    const val HEADER_SIZE = 4

    /** Largest payload the length field can describe. */
    const val MAX_PAYLOAD_SIZE = 0xFFFF

    /**
     * The four header bytes for a [payloadSize]-byte payload of message type [type].
     *
     * @throws IllegalArgumentException if [payloadSize] will not fit. Loud rather than truncating:
     *   a wrapped length desynchronises the receiver permanently.
     */
    fun encodeHeader(payloadSize: Int, type: Int): ByteArray {
        require(payloadSize in 0..MAX_PAYLOAD_SIZE) {
            "WPP payload size $payloadSize does not fit in a uint16 length field"
        }
        require(type in 0..0xFFFF) { "WPP message type $type does not fit in a uint16 type field" }
        return byteArrayOf(
            ((payloadSize shr 8) and 0xFF).toByte(),
            (payloadSize and 0xFF).toByte(),
            ((type shr 8) and 0xFF).toByte(),
            (type and 0xFF).toByte()
        )
    }

    /** A complete frame: header for [payload] of message type [type], then the payload itself. */
    fun encodeFrame(payload: ByteArray, type: Int): ByteArray =
        encodeHeader(payload.size, type) + payload

    /** The payload length declared by a [HEADER_SIZE]-byte header. */
    fun decodePayloadSize(header: ByteArray): Int {
        require(header.size >= HEADER_SIZE) { "WPP header needs $HEADER_SIZE bytes, got ${header.size}" }
        return ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
    }

    /** The message type declared by a [HEADER_SIZE]-byte header. */
    fun decodeType(header: ByteArray): Int {
        require(header.size >= HEADER_SIZE) { "WPP header needs $HEADER_SIZE bytes, got ${header.size}" }
        return ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
    }
}
