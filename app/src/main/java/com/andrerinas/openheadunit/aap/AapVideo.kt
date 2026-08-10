package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.nio.ByteBuffer

internal class AapVideo(private val videoDecoder: VideoDecoder, private val settings: Settings, private val onFrameCorrupted: () -> Unit) {

    companion object {
        /** Enough for H.264 at the resolutions most head units negotiate. ~2MB. */
        private const val INITIAL_BUFFER_BYTES = Messages.DEF_BUFFER_LENGTH * 16

        /** Ceiling for [ensureCapacity]. ~8MB, which covers H.265 at 4K. */
        private const val MAX_BUFFER_BYTES = Messages.DEF_BUFFER_LENGTH * 64
    }

    /**
     * Reassembly buffer for fragmented frames. Grows on demand.
     *
     * It used to be sized up front from settings.videoCodec, which is the user's preference and
     * not the codec the session runs: ServiceDiscoveryResponse negotiates H.265 at 1440p and 4K
     * whatever that setting says, and falls back to H.264 when the user asked for H.265 on a
     * device with no HEVC decoder. Either mismatch handed an H.265 stream the 2MB H.264 buffer and
     * turned every large frame into a fragment overflow. The real answer is also not available
     * here - AapVideo is constructed with the transport, before the phone has negotiated anything
     * - so start small and let a frame that does not fit be the thing that asks for more.
     */
    private var messageBuffer = ByteBuffer.allocate(INITIAL_BUFFER_BYTES)
    private var legacyAssembledBuffer: ByteArray? = null
    private var isFrameCorrupt = false
    private var lastKeyframeRequestMs = 0L
    private var isAssemblingFrame = false

    /**
     * Marks the frame being assembled unusable and asks the phone for a fresh keyframe.
     *
     * Corruption is scoped to the current frame only: flags 8 and 10 skip the rest of it, and the
     * next flag 9 or 11 clears the mark. There is deliberately no lockout that holds every
     * subsequent P-frame back until a keyframe is positively identified - one existed, and because
     * nothing could reliably tell a keyframe from a P-frame for every codec the app negotiates, it
     * could latch for the rest of the session and freeze the picture with the connection still
     * healthy. Dropping one frame and letting the stream heal on the phone's next keyframe is both
     * cheaper and unable to get stuck.
     */
    private fun markCorruptAndRequestRecovery() {
        isFrameCorrupt = true
        val now = android.os.SystemClock.elapsedRealtime()
        if (VideoRecoveryPolicy.canRequestKeyframe(now, lastKeyframeRequestMs)) {
            lastKeyframeRequestMs = now
            AppLog.w("AapVideo: Frame corrupted, requesting keyframe to recover stream")
            onFrameCorrupted()
        }
    }

    private fun checkFragmentState(message: AapMessage) {
        when (val flags = message.flags.toInt()) {
            11, 9 -> {
                // 11 (Single) and 9 (First) should always start a clean slate.
                if (isAssemblingFrame) {
                    AppLog.w("AapVideo: Previous frame was truncated! Resetting assembly state.")
                }
                isAssemblingFrame = (flags == 9) // Only 9 means we are assembling
            }
            8, 10 -> {
                // 8 (Middle) and 10 (Last) MUST belong to an active assembly.
                if (!isAssemblingFrame) {
                    AppLog.e("AapVideo: Orphaned fragment (Flag $flags) detected! Frame data lost.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                    // The mark makes the flag 8/10 arms below skip this fragment. It is cleared
                    // again by the next flag 9 or 11, so the stream resumes on the frame after.
                }
                if (flags == 10) {
                    isAssemblingFrame = false // Assembly finished
                }
            }
        }
    }

    /**
     * Makes room for [additionalBytes] more of the frame being assembled, growing [messageBuffer]
     * if it has to. Returns false only at [MAX_BUFFER_BYTES], where the frame is genuinely too
     * large to be one we can decode and the caller should invalidate it.
     */
    private fun ensureCapacity(additionalBytes: Int): Boolean {
        if (messageBuffer.remaining() >= additionalBytes) return true

        val needed = messageBuffer.position() + additionalBytes
        if (needed > MAX_BUFFER_BYTES) return false

        var capacity = messageBuffer.capacity()
        while (capacity < needed) capacity *= 2
        val grown = ByteBuffer.allocate(capacity.coerceAtMost(MAX_BUFFER_BYTES))
        grown.put(messageBuffer.array(), 0, messageBuffer.position())
        messageBuffer = grown
        AppLog.i("AapVideo: Reassembly buffer grown to ${grown.capacity() / 1024}KB")
        return true
    }

    private fun findStartCode(buf: ByteArray, offset: Int): Int {
        if (offset + 3 > buf.size) return -1
        if (buf[offset].toInt() == 0 && buf[offset + 1].toInt() == 0) {
            if (buf[offset + 2].toInt() == 1) return 3 // 3-byte start code
            if (offset + 4 <= buf.size && buf[offset + 2].toInt() == 0 && buf[offset + 3].toInt() == 1) return 4 // 4-byte start code
        }
        return -1
    }

    fun process(message: AapMessage): Boolean {
        // Fix smearing happening after some while
        checkFragmentState(message)

        val flags = message.flags.toInt()
        val buf = message.data
        val len = message.size

        when (flags) {
            11 -> {
                // Single fragment frame - corruption only affects this frame
                isFrameCorrupt = false
                messageBuffer.clear()

                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    videoDecoder.decode(buf, 10, len - 10, settings.forceSoftwareDecoding, settings.videoCodec)
                    return true
                }

                // Media Indication or Config (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    videoDecoder.decode(buf, 2, len - 2, settings.forceSoftwareDecoding, settings.videoCodec)
                    return true
                }
                AppLog.w("AapVideo: Dropped Flag 11 packet. len=$len")
            }
            9 -> {
                // First fragment - reset corruption state for the new frame
                isFrameCorrupt = false
                messageBuffer.clear()

                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    messageBuffer.put(message.data, 10, message.size - 10)
                    return true
                }
                // Media Indication (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    messageBuffer.put(message.data, 2, message.size - 2)
                    return true
                }
            }
            8 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Middle fragment - append to buffer with overflow detection
                if (ensureCapacity(message.size)) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Fragment overflow (Flag 8)! Size ${message.size} on top of ${messageBuffer.position()} exceeds the ${MAX_BUFFER_BYTES / 1024}KB cap. Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                }
                return true
            }
            10 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Last fragment - append, assemble, and decode
                if (ensureCapacity(message.size)) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Final fragment overflow (Flag 10)! Frame exceeds the ${MAX_BUFFER_BYTES / 1024}KB cap. Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                    return true
                }

                messageBuffer.flip()
                val assembledSize = messageBuffer.limit()

                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                    if (legacyAssembledBuffer == null || legacyAssembledBuffer!!.size < assembledSize) {
                        legacyAssembledBuffer = ByteArray(assembledSize + 1024)
                    }
                    messageBuffer.get(legacyAssembledBuffer!!, 0, assembledSize)
                    videoDecoder.decode(legacyAssembledBuffer!!, 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                } else {
                    videoDecoder.decode(messageBuffer.array(), 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                }

                messageBuffer.clear()
                return true
            }
        }

        return false
    }

    fun release() {
        // Kept for AapTransport lifecycle compatibility. Decoding is synchronous here.
    }
}
