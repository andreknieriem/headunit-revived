package com.andrerinas.openheadunit.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.LegacyOptimizer
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

interface VideoDimensionsListener {
    fun onVideoDimensionsChanged(width: Int, height: Int)
}

/**
 * Main video decoding engine.
 * Handles H.264/H.265 streams via MediaCodec.
 */
class VideoDecoder(private val settings: Settings) {
    companion object {
        private const val TIMEOUT_US = 10000L
        private const val MAX_RESTARTS_WITHOUT_FRAME = 3

        // sync_stall watchdog tuning. An intermittently slow decoder — one that renders, just not
        // within SYNC_STALL_THRESHOLD_MS — escapes restartsSinceLastFrame's cap, which counts only
        // restarts that produced no frame at all and resets the moment one renders. Without its own
        // cooldown and cap this watchdog would rebuild the MediaCodec indefinitely on marginal
        // hardware, the same failure mode
        // AapProjectionActivity.maybeRecoverFromDisplayStall() was hardened against.
        private const val SYNC_STALL_THRESHOLD_MS = 2000L
        private const val SYNC_STALL_COOLDOWN_MS = 8000L
        private const val SYNC_STALL_RESET_MS = 60000L
        private const val MAX_SYNC_STALL_RESTARTS = 4

        // Interval of the decode/render throughput summary. This exists because a slow picture
        // is otherwise unattributable from a user-submitted log: the only rendered-frame count
        // the decoder keeps reaches the opt-in on-screen overlay and never the log, so "the
        // codec is slow" and "the codec keeps up and the display consumer drops the frames"
        // produce byte-identical logs. Reporting rendered alongside fed separates the two.
        private const val THROUGHPUT_LOG_INTERVAL_MS = 5000L

        // How many already-decoded frames the output thread may discard in one pass to reach the
        // newest one. Only frames the codec has *already* finished are eligible, so while it keeps
        // pace nothing is ever waiting and this never triggers; the bound exists purely so a codec
        // handing back a long backlog cannot hold the loop away from its stall checks.
        private const val MAX_CATCHUP_SKIPS = 8

        // Frames that may wait between the transport and the codec. Deep enough to absorb the
        // arrival burst that follows a few hundred milliseconds of wireless silence, around
        // 200ms of video at the rates this negotiates, and no deeper, because everything sitting
        // here is latency between a touch and the picture answering it.
        private const val FRAME_QUEUE_CAPACITY = 12
        private const val FEED_POLL_MS = 200L
        // Floor for pooled frame buffers, so the pool settles at a reusable size instead of
        // reallocating around whatever the first few frames happened to measure.
        private const val MIN_POOLED_FRAME_BYTES = 64 * 1024

        // Throttle for reporting a stall the watchdog saw but declined to act on. Once the
        // cooldown or the restart cap below suppresses a restart, that branch takes no action
        // and would otherwise print nothing, so a decoder degrading past its restart budget is
        // indistinguishable in the log from one that is running perfectly.
        private const val SYNC_STALL_SUPPRESSED_LOG_INTERVAL_MS = 10000L

        // Widest buffer alignment any decoder pads a picture dimension out to. Used to sanity
        // check a reported crop rectangle against the buffer geometry: a real crop is at most
        // this far below the buffer, so anything further off is not describing this stream.
        private const val MAX_ALIGNMENT_PADDING = 64

        /**
         * Checks if H.265 (HEVC) hardware decoding is supported on the current device.
         */
        /**
         * Checks if H.265 (HEVC) hardware decoding is supported and reliable on the current device.
         * Used for AUTO codec selection.
         */
        fun isHevcReliable(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false

            // 1. Chipset Reliability Check (from SystemOptimizer)
            val hw = Build.HARDWARE.lowercase()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.lowercase()
            } else ""

            val isReliable = hw.startsWith("qcom") || hw.startsWith("msm") || // Qualcomm
                    hw.startsWith("exynos") || // Samsung
                    hw.startsWith("gs") || hw.contains("google") || // Google Tensor
                    soc.contains("qualcomm") || soc.contains("samsung") || soc.contains("google") ||
                    // High-end MediaTek (Dimensity 700/800/900/1000/9000+ series)
                    hw.startsWith("mt68") || hw.startsWith("mt69")

            if (!isReliable) return false

            return isHevcSupported()
        }

        /**
         * Checks if ANY H.265 (HEVC) hardware decoding is present, regardless of reliability.
         * Used for MANUAL codec selection (User override).
         */
        // Hardware HEVC support is fixed for a device, but the check scans the whole MediaCodecList,
        // so cache it: this is called on UI-thread paths (resolution/DPI recommendation).
        @Volatile
        private var cachedHwHevcSupported: Boolean? = null

        fun isHevcSupported(): Boolean {
            cachedHwHevcSupported?.let { return it }
            return isHevcDecoderAvailable(includeSoftware = false).also { cachedHwHevcSupported = it }
        }

        /**
         * Checks if H.265 (HEVC) decoding is present.
         *
         * Hardware-only is still the default because software HEVC has no real-time
         * performance guarantee. includeSoftware is used only for explicit user
         * overrides before falling back to bundled/native decoders.
         */
        fun isHevcDecoderAvailable(includeSoftware: Boolean): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false

            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals("video/hevc", ignoreCase = true)) {
                        val name = info.name.lowercase()
                        val isSoftware = name.startsWith("omx.google.") ||
                                name.startsWith("c2.android.") ||
                                name.startsWith("omx.ffmpeg.") ||
                                name.contains(".sw.") ||
                                name.contains("software")

                        if (includeSoftware || !isSoftware) return true
                    }
                }
            }
            return false
        }

        fun isBundledHevcDecoderAvailable(): Boolean {
            return FfmpegHevcDecoder.isAvailable()
        }
    }

    private var codec: MediaCodec? = null
    private var softwareHevcDecoder: FfmpegHevcDecoder? = null
    private var codecBufferInfo: MediaCodec.BufferInfo? = null
    private var mSurface: Surface? = null
    private var outputThread: Thread? = null
    @Volatile private var running = false
    private var startTime = 0L

    private var mWidth = 0
    private var mHeight = 0
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    @Volatile private var codecConfigured = false
    private var currentCodecType = CodecType.H264
    private var currentCodecName: String? = null

    // Once a codec type has been used to successfully start a decoder this session, it is
    // "pinned": restarts reuse it directly instead of re-running detectCodecType() on
    // whatever raw packet happens to be arriving at that instant. detectCodecType()'s NAL-type
    // heuristic can misclassify an ordinary H.264 slice byte as an HEVC VPS/SPS/PPS NAL
    // (e.g. nal_unit_type=1/nal_ref_idc=2 -> byte 0x41 -> 0x41 >> 1 == 32), and re-detecting on
    // every restart let that false positive hijack an otherwise-working H.264 session.
    private var codecTypePinned = false
    private var restartsSinceLastFrame = 0
    private var codecFallbackUsed = false
    private var decoderPermanentlyFailed = false

    // sync_stall cooldown/cap state - see SYNC_STALL_* constants.
    private var syncStallRestartCount = 0
    private var lastSyncStallRestartMs = 0L
    private var lastSyncStallSuppressedLogMs = 0L

    // Throughput counters for the periodic telemetry tick - see THROUGHPUT_LOG_INTERVAL_MS.
    // Session-monotonic so each has exactly one writer: framesFed/inputWaitMs from the feed
    // thread, framesRendered/framesSkippedAtRender from the output thread. framesDropped is the
    // exception - both the feed thread (codec would not take the frame) and decode() (queue full)
    // count into it, and they are ordered by the queue rather than by a lock. It drives a log
    // line, so a lost increment costs a digit in a report and nothing else. The output thread
    // reads them all and keeps its own last-logged snapshots to derive per-interval deltas.
    @Volatile private var framesFed = 0L
    @Volatile private var framesDropped = 0L
    @Volatile private var framesRendered = 0L
    // Decoded frames discarded unshown to reach a newer one. Distinct from framesDropped, which
    // counts frames that never reached the decoder at all: these cost no picture quality, only
    // the motion they would have shown, and a non-zero count is the link arriving in bursts.
    @Volatile private var framesSkippedAtRender = 0L
    // Milliseconds spent waiting for a free input buffer, on the feed thread. Otherwise invisible,
    // since a frame that waits some of its attempts logs nothing and "Input buffer full" only fires
    // when every attempt is exhausted. Near zero while the frame rate is low means the frames were
    // never sent; high means frames are arriving faster than the codec drains them, which on a
    // healthy link is the signature of a burst after the link went quiet.
    @Volatile private var inputWaitMs = 0L
    private var lastThroughputLogMs = 0L
    private var lastLoggedFramesFed = 0L
    private var lastLoggedFramesDropped = 0L
    private var lastLoggedFramesRendered = 0L
    private var lastLoggedFramesSkippedAtRender = 0L
    private var lastLoggedInputWaitMs = 0L

    // Encoded frames waiting to be handed to the codec, and the thread that hands them over.
    //
    // This queue exists to keep the wait for a free codec input buffer off the transport's read
    // thread. That thread carries every channel - video, audio, microphone, control - so while it
    // sat inside dequeueInputBuffer nothing else on the link was dispatched and the socket went
    // undrained, which turned a busy decoder into stalled audio and late keepalives. Frames are
    // copied on the way in because the transport reuses the buffer it hands us.
    private class PendingFrame(var data: ByteArray, var size: Int, var arrivalNanos: Long)
    private val frameQueue = ArrayBlockingQueue<PendingFrame>(FRAME_QUEUE_CAPACITY)
    private val framePool = ArrayBlockingQueue<PendingFrame>(FRAME_QUEUE_CAPACITY + 2)
    // Volatile and identity-checked by the loop itself: interrupt() does not abort a MediaCodec
    // call, so a feed thread parked in dequeueInputBuffer can outlive the join() below. If it
    // does, stop() goes on to release the codec and a later start() sets running back to true -
    // at which point a loop that only tested running would wake up and feed the *new* codec
    // alongside the new thread, interleaving two frames into one input queue and inverting decode
    // order. Testing identity as well means an outlived thread can only ever exit.
    @Volatile private var feedThread: Thread? = null

    // Scratch for the output thread's catch-up pass. Owned by that thread alone.
    private val readyIndices = IntArray(MAX_CATCHUP_SKIPS + 2)

    // Reuse buffers for older API levels to minimize GC pressure
    private var inputBuffers: Array<ByteBuffer>? = null
    private var legacyFrameBuffer: ByteArray? = null

    var dimensionsListener: VideoDimensionsListener? = null
    var onFpsChanged: ((Int) -> Unit)? = null
    var softwareYuvFrameSink: SoftwareYuvFrameSink? = null
    private var frameCount = 0
    private var lastFpsLogTime = 0L
    private var loggedFirstSoftwareFrame = false
    private var loggedFirstHardwareFrame = false
    @Volatile var onFirstFrameListener: (() -> Unit)? = null
    @Volatile var lastFrameRenderedMs: Long = 0L

    // Frames rendered since whoever owns the session last zeroed this. Deliberately *not* cleared
    // by stop(), which is what separates it from framesRendered above: that one is a throughput
    // counter and must not straddle a restart, this one must survive every restart the session
    // contains. The surface goes away and comes back within a single session (leaving projection,
    // screen off, a config change), and both stop() and setSurface() zero lastFrameRenderedMs when
    // it does, so reading that to ask "did this session ever show video" answers no for a session
    // that showed plenty. See CommManager.noteSessionEnded.
    @Volatile var framesRenderedThisSession: Long = 0L

    // elapsedRealtime() of the last encoded video bytes received from the phone (input side),
    // as opposed to lastFrameRenderedMs (output side). Lets the projection watchdog tell a
    // phone-side pause (no input) apart from a local display stall (input flowing, nothing drawn).
    @Volatile var lastInputBytesReceivedMs: Long = 0L

    // True while the bundled software HEVC decoder is active. That path renders through the
    // GLES YUV sink, so the projection watchdog must not fall back to a non-GLES backend.
    val usingBundledSoftwareHevc: Boolean get() = softwareHevcDecoder != null

    @Volatile private var decoderNeedsRestart = false
    @Volatile private var decoderRestartReason: String? = null
    @Volatile private var pendingKeyframeRequest = false

    // Callback for transport layer integration
    var onDecoderError: (() -> Unit)? = null

    val videoWidth: Int get() = mWidth
    val videoHeight: Int get() = mHeight

    enum class CodecType(val mimeType: String, val displayName: String, val settingsValue: String) {
        H264("video/avc", "H.264/AVC", "H.264"),
        H265("video/hevc", "H.265/HEVC", "H.265")
    }

    /**
     * The visible picture size described by a decoder output format.
     *
     * KEY_WIDTH/KEY_HEIGHT carry the buffer geometry, which vendors are free to pad out to their
     * macroblock alignment - Intel's AVC component reports 736 for a 720-line stream. Taking that
     * verbatim propagates a video size the stream does not have: the projection view scales to an
     * aspect ratio that is off by the padding, and a later restart reconfigures MediaCodec for the
     * padded height. The crop rectangle is the authoritative visible region, so prefer it and fall
     * back to the buffer geometry only where a decoder omits it.
     */
    private fun displaySizeOf(format: MediaFormat): Pair<Int, Int> {
        fun key(name: String): Int? =
            try { if (format.containsKey(name)) format.getInteger(name) else null } catch (e: Exception) { null }

        val bufferWidth = key(MediaFormat.KEY_WIDTH) ?: mWidth
        val bufferHeight = key(MediaFormat.KEY_HEIGHT) ?: mHeight

        // Inclusive bounds, so the visible extent is right - left + 1.
        val cropLeft = key("crop-left")
        val cropRight = key("crop-right")
        val cropTop = key("crop-top")
        val cropBottom = key("crop-bottom")

        val cropWidth = if (cropLeft != null && cropRight != null) cropRight - cropLeft + 1 else 0
        val cropHeight = if (cropTop != null && cropBottom != null) cropBottom - cropTop + 1 else 0

        return croppedOr(cropWidth, bufferWidth) to croppedOr(cropHeight, bufferHeight)
    }

    /**
     * [crop] if it is a plausible visible extent for a buffer of [buffer] pixels, else [buffer].
     *
     * Padding only ever rounds a dimension *up* to an alignment boundary, and no decoder aligns
     * more coarsely than [MAX_ALIGNMENT_PADDING], so a trustworthy crop sits just below the buffer
     * size. Anything further away is a decoder filling the crop keys with placeholders rather than
     * describing this stream - zeros are the dangerous case, since a 0..0 crop reads as a legal
     * 1-pixel extent and would otherwise be propagated as the video size and handed to
     * MediaCodec on the next restart.
     */
    private fun croppedOr(crop: Int, buffer: Int): Int =
        if (buffer > 0 && crop in (buffer - MAX_ALIGNMENT_PADDING)..buffer) crop else buffer

    /**
     * Handles dynamic video dimension changes during the session.
     */
    private fun handleOutputFormatChange(format: MediaFormat) {
        AppLog.i("Output Format Changed: $format")
        val (newWidth, newHeight) = displaySizeOf(format)
        if (mWidth != newWidth || mHeight != newHeight) {
            AppLog.i("Video dimensions changed via format: ${newWidth}x$newHeight")
            mWidth = newWidth
            mHeight = newHeight
            dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
        }
        try {
            codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        } catch (e: Exception) {}
    }

    /**
     * Sets the rendering surface and restarts the decoder if necessary.
     */
    fun setSurface(surface: Surface?) {
        synchronized(this) {
            if (mSurface === surface) return

            AppLog.i("New surface set: $surface")
            if (codec != null || softwareHevcDecoder != null) {
                stop(DecoderStopPolicy.REASON_NEW_SURFACE)
            }
            mSurface = surface
            lastFrameRenderedMs = 0L
        }
    }

    /**
     * Stops the decoder, terminates the output thread, and releases hardware resources.
     */
    fun stop(reason: String = "unknown") {
        synchronized(this) {
            running = false
            try {
                // If calling from output thread, don't join itself to avoid deadlock
                if (outputThread != null && outputThread != Thread.currentThread()) {
                    outputThread?.interrupt()
                    outputThread?.join(500)
                }
            } catch (e: Exception) {}
            outputThread = null

            // Shorter than the output thread's join because overrunning it is safe here: the loop
            // checks its own identity against feedThread, so once this clears it a thread still
            // parked in MediaCodec can only exit. Kept short because stop() holds this object's
            // monitor and setSurface() reaches it from the main thread.
            try {
                if (feedThread != null && feedThread != Thread.currentThread()) {
                    feedThread?.interrupt()
                    feedThread?.join(200)
                }
            } catch (e: Exception) {}
            feedThread = null
            clearFrameQueue()
            // Pooled frames grow to the largest ever seen and never shrink, so a 4K session would
            // otherwise pin that size for the life of the process.
            framePool.clear()

            try {
                codec?.stop()
            } catch (e: Exception) {}
            try {
                codec?.release()
            } catch (e: Exception) {
                AppLog.e("Error releasing decoder", e)
            }
            try {
                softwareHevcDecoder?.stop()
            } catch (e: Exception) {
                AppLog.e("Error releasing software HEVC decoder", e)
            }

            codec = null
            softwareHevcDecoder = null
            inputBuffers = null
            legacyFrameBuffer = null
            codecBufferInfo = null
            codecConfigured = false
            if (!DecoderStopPolicy.isDecoderRestart(reason)) {
                vps = null
                sps = null
                pps = null
                mWidth = 0
                mHeight = 0
                restartsSinceLastFrame = 0
                codecFallbackUsed = false
                decoderPermanentlyFailed = false
                syncStallRestartCount = 0
                lastSyncStallRestartMs = 0L
            }
            // The pinned codec type describes the stream, not the decoder instance, so it has to
            // outlive a surface teardown: the phone keeps sending the same codec while the view is
            // rebuilt, and re-detecting on whatever packet lands mid-teardown can misread an
            // ordinary H.264 P-slice as HEVC and configure the wrong decoder for the rest of the
            // session. Only a real disconnect can change what the phone is sending.
            if (DecoderStopPolicy.endsSession(reason)) {
                codecTypePinned = false
            }
            // Keep VPS/SPS/PPS cached so we can re-inject them on restart
            lastFrameRenderedMs = 0L
            loggedFirstSoftwareFrame = false
            loggedFirstHardwareFrame = false
            // The FPS window and the throughput counters must not straddle a restart, or the
            // first sample afterwards is averaged over the whole teardown and reads near zero.
            frameCount = 0
            lastFpsLogTime = 0L
            framesFed = 0L
            framesDropped = 0L
            framesRendered = 0L
            framesSkippedAtRender = 0L
            inputWaitMs = 0L
            lastThroughputLogMs = 0L
            lastLoggedFramesFed = 0L
            lastLoggedFramesDropped = 0L
            lastLoggedFramesRendered = 0L
            lastLoggedFramesSkippedAtRender = 0L
            lastLoggedInputWaitMs = 0L
            AppLog.i("Decoder stopped: $reason")
        }
    }

    private fun scheduleRestart(reason: String) {
        decoderRestartReason = reason
        decoderNeedsRestart = true
    }

    /**
     * Main entry point for decoding a video/control packet.
     *
     * Reports nothing back to the caller. A frame this cannot place into the codec's input queue
     * is simply lost, which the stream heals from on the phone's next keyframe; treating it as
     * stream corruption instead put a keyframe request and a video-focus cycle behind an event
     * that fires within a second of the decoder starting on ordinary hardware.
     */
    fun decode(buffer: ByteArray, offset: Int, size: Int, forceSoftware: Boolean, codecName: String) {
        synchronized(this) {
            // Input-side liveness: bytes are arriving from the phone right now.
            lastInputBytesReceivedMs = SystemClock.elapsedRealtime()

            // Check if a restart was requested by output thread
            if (decoderNeedsRestart) {
                AppLog.w("Decoder restart requested: $decoderRestartReason")

                // Track restarts that never produced a single frame for the currently pinned
                // codec type. A genuinely broken hardware decoder (e.g. an MTK HEVC component
                // that can't configure at all) will keep failing here forever otherwise.
                if (codecTypePinned && lastFrameRenderedMs == 0L) {
                    restartsSinceLastFrame++
                    if (restartsSinceLastFrame >= MAX_RESTARTS_WITHOUT_FRAME) {
                        if (!codecFallbackUsed) {
                            val fallbackType = if (currentCodecType == CodecType.H264) CodecType.H265 else CodecType.H264
                            // Don't burn the one-time fallback on a codec type the device has no
                            // decoder for at all (hw or sw) - e.g. HEVC on a pre-Lollipop device
                            // with zero HEVC components. That flip is guaranteed to fail again.
                            if (findBestCodec(fallbackType.mimeType, true) == null) {
                                AppLog.e("${currentCodecType.displayName} failed $restartsSinceLastFrame times in a row without rendering a frame, and this device has no decoder for ${fallbackType.displayName} either. Giving up to avoid an infinite restart loop.")
                                decoderPermanentlyFailed = true
                            } else {
                                AppLog.e("${currentCodecType.displayName} failed $restartsSinceLastFrame times in a row without rendering a frame. Falling back to ${fallbackType.displayName}.")
                                currentCodecType = fallbackType
                                codecFallbackUsed = true
                                restartsSinceLastFrame = 0
                            }
                        } else {
                            AppLog.e("Both codec types failed to render a frame this session. Giving up to avoid an infinite restart loop.")
                            decoderPermanentlyFailed = true
                        }
                    }
                } else if (lastFrameRenderedMs != 0L) {
                    // Decoder was healthy before this restart; don't count it against the pin.
                    restartsSinceLastFrame = 0
                }

                stop("restart: $decoderRestartReason")
                decoderNeedsRestart = false
                decoderRestartReason = null
                onDecoderError?.invoke()
            }

            if (decoderPermanentlyFailed) return

            // Buffer management for backward compatibility
            // Modern devices (API 21+) use the original buffer with offset/size to avoid GC pressure.
            val frameData: ByteArray
            val frameOffset: Int
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                if (legacyFrameBuffer == null || legacyFrameBuffer!!.size < size) {
                    legacyFrameBuffer = ByteArray(size + 1024)
                }
                System.arraycopy(buffer, offset, legacyFrameBuffer!!, 0, size)
                frameData = legacyFrameBuffer!!
                frameOffset = 0
            } else {
                frameData = buffer
                frameOffset = offset
            }


            // Initialization phase: detect codec and configuration (SPS/PPS).
            // Only runs the detection heuristic on the very first init of the session; once a
            // codec type is pinned (see codecTypePinned), restarts reuse it directly instead of
            // re-sniffing arbitrary mid-stream bytes.
            if (codec == null && softwareHevcDecoder == null) {
                val typeToUse = if (codecTypePinned) {
                    currentCodecType
                } else {
                    val detectedType = detectCodecType(frameData, frameOffset, size)
                    val requestedType = if (codecName.contains("265")) CodecType.H265 else CodecType.H264
                    if (requestedType == CodecType.H265) {
                        CodecType.H265
                    } else {
                        detectedType ?: requestedType
                    }
                }
                currentCodecType = typeToUse

                if (!codecConfigured) {
                    scanAndApplyConfig(frameData, frameOffset, size, typeToUse)

                    if (mWidth == 0) {
                         // Fallback dimensions if SPS/PPS parsing fails or is missing
                         val negotiatedW = HeadUnitScreenConfig.getNegotiatedWidth()
                         val negotiatedH = HeadUnitScreenConfig.getNegotiatedHeight()
                         if (negotiatedW > 0 && negotiatedH > 0) {
                             AppLog.i("Fallback to negotiated dimensions: ${negotiatedW}x${negotiatedH}")
                             mWidth = negotiatedW
                             mHeight = negotiatedH
                             dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                         }
                    }
                }

                if (mSurface == null || !mSurface!!.isValid) return
                if (mWidth == 0 || mHeight == 0) return

                if (shouldUseBundledHevc(typeToUse, settings.forceSoftwareDecoding || forceSoftware)) {
                    startBundledHevc(mWidth, mHeight)
                } else {
                    start(typeToUse.mimeType, settings.forceSoftwareDecoding || forceSoftware, mWidth, mHeight)
                }
            }

            softwareHevcDecoder?.let { decoder ->
                val renderedFrames = decoder.decode(frameData, frameOffset, size)
                if (renderedFrames > 0) {
                    onSoftwareFramesRendered(renderedFrames)
                } else if (renderedFrames < 0) {
                    AppLog.e("Bundled HEVC decoder failed with code $renderedFrames")
                    scheduleRestart("software_hevc_error_$renderedFrames")
                }
                return
            }

            if (codec == null) return

            if (pendingKeyframeRequest) {
                pendingKeyframeRequest = false
                AppLog.i("Decoder restarted and ready. Invoking error callback to request keyframe.")
                onDecoderError?.invoke()
            }

            enqueueForFeed(frameData, frameOffset, size)
        }
    }

    /**
     * Copies a frame onto the feed queue. Returns immediately in every case - the caller is the
     * transport's read thread and must get back to the socket.
     */
    private fun enqueueForFeed(frameData: ByteArray, frameOffset: Int, size: Int) {
        // An empty frame reaches the codec as nothing at all, so queueing it would only inflate
        // the fed count that the throughput line uses to say whether frames arrived.
        if (size <= 0) return

        val frame = framePool.poll()?.let { pooled ->
            if (pooled.data.size < size) pooled.data = ByteArray(maxOf(size, MIN_POOLED_FRAME_BYTES))
            pooled
        } ?: PendingFrame(ByteArray(maxOf(size, MIN_POOLED_FRAME_BYTES)), 0, 0L)

        System.arraycopy(frameData, frameOffset, frame.data, 0, size)
        frame.size = size
        // Stamped here, not where the frame is handed over: the feed thread drains a backlog in
        // a few milliseconds, so timestamps taken there would give a dozen frames near-identical
        // values and flatten the cadence the codec sees.
        frame.arrivalNanos = System.nanoTime()

        if (!frameQueue.offer(frame)) {
            // The codec has not taken a frame for as long as this queue holds. Drop the one that
            // just arrived rather than something already queued: the older frames are what
            // everything after them is decoded against, so dropping forward costs one frame while
            // dropping backward corrupts every frame that referenced it until the next keyframe -
            // and on this protocol those are seconds apart and cannot be asked for cheaply.
            framePool.offer(frame)
            framesDropped++
        }
    }

    /**
     * Drains the feed queue into the codec. Deliberately does not hold this object's monitor:
     * stop() does, and joins this thread while holding it. Follows the output thread's contract
     * instead - read [codec] once, and let stop() clear [running] and interrupt before releasing.
     */
    private fun feedThreadLoop() {
        AppLog.i("Feed thread started")
        val self = Thread.currentThread()
        while (running && feedThread === self) {
            val frame = try {
                frameQueue.poll(FEED_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue

            try {
                if (!running || feedThread !== self || codec == null) continue
                val buf = ByteBuffer.wrap(frame.data, 0, frame.size)
                var fed = true
                while (buf.hasRemaining()) {
                    if (!feedInputBuffer(buf, frame.arrivalNanos)) {
                        // A teardown that lands mid-frame fails the same way a full queue does.
                        // Say nothing in that case: the frame is moot and the log line would
                        // appear on every ordinary stop.
                        if (!running || feedThread !== self || codec == null) {
                            fed = false
                            break
                        }
                        // The codec had no free input buffer for the whole wait. Drop what is left
                        // of this frame and carry on: the next keyframe repairs the picture, and
                        // the decoders this happens on are the ones least able to afford the
                        // alternative. Routing it into the recovery path instead cost a keyframe
                        // request and a video-focus cycle for an event that fires within a second
                        // of the decoder starting on ordinary hardware. A decoder that is genuinely
                        // stuck rather than busy is still caught by the sync_stall watchdog.
                        AppLog.w("Input buffer full. Dropping frame.")
                        framesDropped++
                        fed = false
                        break
                    }
                }
                if (fed) framesFed++
            } catch (e: Exception) {
                AppLog.w("Feed thread error: ${e.message}")
            } finally {
                framePool.offer(frame)
            }
        }
        AppLog.i("Feed thread stopped")
    }

    /** Empties the feed queue back into the pool. Anything still waiting is stale after a stop. */
    private fun clearFrameQueue() {
        while (true) {
            val frame = frameQueue.poll() ?: break
            framePool.offer(frame)
        }
    }

    private fun shouldUseBundledHevc(type: CodecType, forceSoftware: Boolean): Boolean {
        return type == CodecType.H265 &&
                forceSoftware &&
                settings.softwareVideoDecoder == Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG &&
                FfmpegHevcDecoder.isAvailable()
    }

    private fun startBundledHevc(width: Int, height: Int) {
        try {
            val surface = mSurface ?: return
            AppLog.i("Configuring bundled FFmpeg HEVC decoder for ${width}x$height")
            val yuvFrameSink = if (settings.viewMode == Settings.ViewMode.GLES) {
                softwareYuvFrameSink
            } else {
                null
            }
            val decoder = FfmpegHevcDecoder(
                surface = if (yuvFrameSink == null) surface else null,
                yuvFrameSink = yuvFrameSink,
                width = width,
                height = height
            )
            if (!decoder.start()) {
                AppLog.e("Bundled FFmpeg HEVC decoder is unavailable")
                return
            }
            softwareHevcDecoder = decoder
            currentCodecName = "ffmpeg-hevc"
            running = true
            startTime = System.nanoTime()
            codecTypePinned = true
            AppLog.i("Bundled FFmpeg HEVC decoder initialized")
        } catch (e: Exception) {
            AppLog.e("Failed to start bundled FFmpeg HEVC decoder", e)
            softwareHevcDecoder = null
            running = false
            scheduleRestart("bundled_hevc_start_failed: ${e.message}")
        }
    }

    private fun onSoftwareFramesRendered(renderedFrames: Int) {
        lastFrameRenderedMs = SystemClock.elapsedRealtime()
        framesRenderedThisSession += renderedFrames
        if (!loggedFirstSoftwareFrame) {
            loggedFirstSoftwareFrame = true
            AppLog.i("First bundled software HEVC frame rendered")
        }
        onFirstFrameListener?.let { it(); onFirstFrameListener = null }

        frameCount += renderedFrames
        // The bundled decoder has no output thread, so drive the throughput tick from here.
        framesRendered += renderedFrames
        logThroughput()
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsLogTime
        if (elapsed >= 1000) {
            if (lastFpsLogTime != 0L) {
                val fps = (frameCount * 1000 / elapsed).toInt()
                onFpsChanged?.invoke(fps)
            }
            frameCount = 0
            lastFpsLogTime = now
        }
    }

    private fun detectCodecType(buffer: ByteArray, offset: Int, size: Int): CodecType? {
        if (size < 5) return null
        val limit = offset + size
        // Need at least 5 bytes visible from position i: [0, 0, 0/1, 1, NAL_HEADER]
        for (i in offset until limit - 4) {
            if (buffer[i].toInt() == 0 && buffer[i+1].toInt() == 0) {
                val headerPos: Int
                if (buffer[i+2].toInt() == 0 && buffer[i+3].toInt() == 1) {
                    headerPos = i + 4
                } else if (buffer[i+2].toInt() == 1) {
                    headerPos = i + 3
                } else continue
                if (headerPos >= limit) return null
                val b = buffer[headerPos].toInt()
                val avcType = b and 0x1F
                if (avcType == 7 || avcType == 8) return CodecType.H264

                val hevcType = (b and 0x7E) shr 1
                if (hevcType in 32..34 && isHevcSupported()) return CodecType.H265
            }
            // Only scan the first ~100 bytes for performance
            if (i - offset >= 96) break
        }
        return null
    }

    /**
     * Splits a combined packet into multiple NAL units and normalizes start codes.
     */
    private fun forEachNalUnit(buffer: ByteArray, offset: Int, size: Int, callback: (ByteArray, Int) -> Unit) {
        var currentPos = offset
        val limit = offset + size

        while (currentPos < limit - 3) {
            var nalStart = -1
            var startCodeLen = 0

            for (i in currentPos until limit - 3) {
                if (buffer[i].toInt() == 0 && buffer[i+1].toInt() == 0) {
                    if (buffer[i+2].toInt() == 0 && buffer[i+3].toInt() == 1) {
                        nalStart = i; startCodeLen = 4; break
                    } else if (buffer[i+2].toInt() == 1) {
                        nalStart = i; startCodeLen = 3; break
                    }
                }
            }

            if (nalStart != -1) {
                var nalEnd = limit
                for (j in (nalStart + startCodeLen) until limit - 3) {
                    if (buffer[j].toInt() == 0 && buffer[j+1].toInt() == 0 &&
                        (buffer[j+2].toInt() == 1 || (buffer[j+2].toInt() == 0 && buffer[j+3].toInt() == 1))) {
                        nalEnd = j; break
                    }
                }

                val rawNal = buffer.copyOfRange(nalStart, nalEnd)
                val fixedNal = if (startCodeLen == 3) {
                    // Normalize to 4-byte start codes for better decoder compatibility
                    ByteArray(rawNal.size + 1).apply {
                        this[0] = 0; System.arraycopy(rawNal, 0, this, 1, rawNal.size)
                    }
                } else rawNal

                callback(fixedNal, if (startCodeLen == 3) 4 else 4)
                currentPos = nalEnd
            } else break
        }
    }

    /**
     * Extracts SPS/PPS/VPS data for the decoder configuration (CSD).
     */
    private fun scanAndApplyConfig(buffer: ByteArray, offset: Int, size: Int, type: CodecType) {
        forEachNalUnit(buffer, offset, size) { nalData, headerLen ->
            val nalFirstByte = nalData[headerLen].toInt()
            if (type == CodecType.H264) {
                val nalType = nalFirstByte and 0x1F
                if (nalType == 7) { // SPS
                    sps = nalData
                    try {
                        val offsetInNal = if (sps!![2].toInt() == 1) 3 else 4
                        SpsParser.parse(sps!!, offsetInNal, sps!!.size - offsetInNal)?.let {
                            if (mWidth != it.width || mHeight != it.height) {
                                AppLog.i("H.264 SPS parsed: ${it.width}x${it.height}")
                                mWidth = it.width; mHeight = it.height
                                dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                            }
                        }
                    } catch (e: Exception) { AppLog.e("Failed to parse SPS data", e) }
                } else if (nalType == 8) pps = nalData // PPS

                // H.264 requires at least SPS to start
                if (sps != null) codecConfigured = true
            } else {
                val nalType = (nalFirstByte and 0x7E) shr 1
                if (nalType == 32) vps = nalData
                else if (nalType == 33) sps = nalData
                else if (nalType == 34) pps = nalData

                // H.265 requires VPS and SPS to start reliably
                if (vps != null && sps != null) codecConfigured = true
            }
        }
    }

    /**
     * Configures and starts the native MediaCodec.
     */
    private fun start(mimeType: String, forceSoftware: Boolean, width: Int, height: Int) {
        try {
            startTime = System.nanoTime()
            val bestCodec = findBestCodec(mimeType, !forceSoftware)
                ?: throw IllegalStateException("No decoder available for $mimeType")
            this.currentCodecName = bestCodec

            codec = MediaCodec.createByCodecName(bestCodec)
            codecBufferInfo = MediaCodec.BufferInfo()

            val format = MediaFormat.createVideoFormat(mimeType, width, height)

            // Deliberately no KEY_PRIORITY / KEY_OPERATING_RATE / KEY_FRAME_RATE / KEY_MAX_B_FRAMES
            // here. They were added as latency hints and measured to do nothing: the OMX components
            // on the head units they were meant to help answer with
            // "codec does not support config priority (err -1010)" and the same for the operating
            // rate. The frame-rate keys are worse than inert, because the only frame rate this
            // class knows is settings.fpsLimit - the user's cap, not the rate the phone negotiated
            // - and KEY_MAX_B_FRAMES is an encoder key. Any replacement needs a log from a device
            // where the codec actually accepts it.

            // Apply Codec Specific Data (CSD) from parsed SPS/PPS/VPS
            if (mimeType == CodecType.H265.mimeType) {
                val combined = (vps ?: byteArrayOf()) + (sps ?: byteArrayOf()) + (pps ?: byteArrayOf())
                if (combined.isNotEmpty()) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(combined))
                }
                // [BUG_FIX] Dynamic buffer size based on resolution.
                // 8MB is too large for many older 1080p decoders (Allwinner/Rockchip),
                // but we need it for 4K.
                val maxInputSize = if (width * height > 1920 * 1080) {
                    8 * 1024 * 1024
                } else {
                    2 * 1024 * 1024
                }
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
            } else {
                if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
                if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))

                // [BUG_FIX] Lower buffer for legacy devices (Android < 9) to prevent startup stalls
                val maxInputSize = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    1 * 1024 * 1024 // 1MB for legacy
                } else {
                    2 * 1024 * 1024 // 2MB for modern
                }
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
            }

            if (!mSurface!!.isValid) throw IllegalStateException("Surface not valid")

            val isAllwinner = bestCodec.lowercase(Locale.ROOT).contains("allwinner")
            if (isAllwinner) {
                // [BUG_FIX] Allwinner decoders often fail on adaptive playback initialization,
                // leading to a SIGABRT in CodecLooper when the surface reconfigures for padding (e.g. 1080->1088).
                AppLog.i("Decoder: Applying Allwinner stability patches.")
                format.setInteger("adaptive-playback", 0)

                if (mimeType == CodecType.H265.mimeType) {
                    AppLog.w("CAUTION: Allwinner H.265 is known to be unstable. If the app crashes, please switch to H.264 in settings.")
                    // Force macroblock alignment (multiple of 16) to prevent re-padding crash
                    val alignedHeight = ((height + 15) / 16) * 16
                    if (alignedHeight != height) {
                        AppLog.i("Decoder: Aligning Allwinner H.265 height to $alignedHeight (was $height)")
                        format.setInteger(MediaFormat.KEY_HEIGHT, alignedHeight)
                    }
                }

                // Explicitly set color format to surface to help ACodec
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            AppLog.i("Configuring decoder: $bestCodec for ${width}x${height}")
            codec?.configure(format, mSurface, null, 0)
            try { codec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT) } catch (e: Exception) {}
            codec?.start()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                @Suppress("DEPRECATION") inputBuffers = codec?.inputBuffers
            }

            running = true
            clearFrameQueue()
            outputThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                LegacyOptimizer.setHighPriority()
                outputThreadLoop()
            }.apply { name = "VideoDecoder-Output"; start() }
            feedThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                feedThreadLoop()
            }.apply { name = "VideoDecoder-Feed"; start() }

            codecTypePinned = true
            AppLog.i("Codec initialized: $bestCodec")
        } catch (e: Exception) {
            AppLog.e("Failed to start decoder", e)
            codec = null; running = false
            scheduleRestart("decoder_start_failed: ${e.message}")
        }
    }

    /**
     * Logic to identify chipsets that require constant flagging
     */
    private fun shouldAlwaysFlagConfig(): Boolean {
        val name = currentCodecName?.lowercase(Locale.ROOT) ?: return false
        return name.contains(".rk.") ||       // Rockchip
                name.contains("allwinner") ||
                name.contains(".tcc.")      // Telechips
    }

    /**
     * Checks if the data contains SPS/PPS/VPS configuration data.
     */
    private fun isCodecConfigData(data: ByteArray, offset: Int, size: Int): Boolean {
        if (size < 5) return false
        for (i in offset until (offset + size - 4).coerceAtMost(offset + 32)) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val headerPos: Int
                if (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    headerPos = i + 4
                } else if (data[i + 2].toInt() == 1) {
                    headerPos = i + 3
                } else continue
                if (headerPos >= offset + size) return false
                val b = data[headerPos].toInt()
                if (currentCodecType == CodecType.H265) {
                    val nalType = (b and 0x7E) shr 1
                    return nalType in 32..34
                } else {
                    val nalType = b and 0x1F
                    return nalType == 7 || nalType == 8
                }
            }
        }
        return false
    }

    /**
     * Feeds the raw byte stream into the decoder buffer.
     */
    private fun feedInputBuffer(buffer: ByteBuffer, arrivalNanos: Long): Boolean {
        val currentCodec = codec ?: return false
        try {
            var inputIndex = -1
            var attempts = 0
            // ~300ms of patience. Anything much shorter gives up while the codec is merely busy:
            // at 30ms this reported a full input queue within a second of every decoder start,
            // before the component had drained its first buffers, on hardware that then went on to
            // decode at full rate.
            //
            // The whole dequeue is timed, not just the retries: the first call blocks for up to
            // TIMEOUT_US on its own, and what holds up audio behind us is the total.
            val waitStart = SystemClock.elapsedRealtime()
            while (attempts < 30) {
                inputIndex = currentCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) break
                attempts++
            }
            inputWaitMs += SystemClock.elapsedRealtime() - waitStart

            if (inputIndex < 0) {
                AppLog.e("Input buffer feed failed (full)")
                return false
            }

            val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                currentCodec.getInputBuffer(inputIndex)
            } else {
                @Suppress("DEPRECATION") inputBuffers?.get(inputIndex)
            }

            if (inputBuffer == null) return false
            inputBuffer.clear()

            val capacity = inputBuffer.capacity()

            // Always set BUFFER_FLAG_CODEC_CONFIG for config data (VPS/SPS/PPS).
            // Some decoders (Rockchip/Allwinner) require this flag for every config packet
            // even after the stream has already started.
            val isConfig = buffer.hasArray() && isCodecConfigData(buffer.array(), buffer.position(), buffer.remaining())
            val flags = if (isConfig && (shouldAlwaysFlagConfig() || !codecConfigured)) {
                MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else 0

            if (buffer.remaining() <= capacity) {
                inputBuffer.put(buffer)
            } else {
                AppLog.w("Frame too large: ${buffer.remaining()} > $capacity. Truncating!")
                val limit = buffer.limit()
                buffer.limit(buffer.position() + capacity)
                inputBuffer.put(buffer)
                buffer.limit(limit)
            }

            inputBuffer.flip()

            val pts = ((if (arrivalNanos > 0L) arrivalNanos else System.nanoTime()) - startTime) / 1000

            currentCodec.queueInputBuffer(inputIndex, 0, inputBuffer.limit(), pts, flags)
            return true
        } catch (e: Exception) {
            AppLog.e("Error feeding input buffer", e)
            return false
        }
    }

    /**
     * Periodic decode/render throughput summary.
     *
     * `rendered` counts buffers actually handed to the surface and `fed` counts frames accepted
     * into the codec's input queue, so the two together locate a slow picture: `fed` high with
     * `rendered` low is a slow codec, both high is a display consumer that cannot keep up with
     * one, and `dropped` rising is input-queue backpressure.
     *
     * `skipped` counts decoded frames discarded to reach a newer one, so it separates the two
     * shapes of a low `rendered`: with `skipped` high the frames arrived in a burst and the older
     * ones were deliberately passed over, with it near zero they never arrived at all.
     *
     * Called from the output thread on every loop iteration. That loop turns over at least every
     * 10ms (dequeueOutputBuffer's timeout) whether or not a frame came out, so the tick still
     * fires while nothing is rendering - which is the case most worth reporting.
     */
    private fun logThroughput() {
        val now = SystemClock.elapsedRealtime()
        if (lastThroughputLogMs == 0L) {
            lastThroughputLogMs = now
            return
        }
        val elapsed = now - lastThroughputLogMs
        if (elapsed < THROUGHPUT_LOG_INTERVAL_MS) return

        val rendered = framesRendered - lastLoggedFramesRendered
        val fed = framesFed - lastLoggedFramesFed
        val dropped = framesDropped - lastLoggedFramesDropped
        val skipped = framesSkippedAtRender - lastLoggedFramesSkippedAtRender
        val inputWait = inputWaitMs - lastLoggedInputWaitMs
        lastThroughputLogMs = now
        lastLoggedFramesRendered = framesRendered
        lastLoggedFramesFed = framesFed
        lastLoggedFramesDropped = framesDropped
        lastLoggedFramesSkippedAtRender = framesSkippedAtRender
        lastLoggedInputWaitMs = inputWaitMs

        val renderedFps = rendered * 1000 / elapsed
        val fedFps = fed * 1000 / elapsed
        AppLog.i(
            "Throughput over ${elapsed}ms: rendered=$rendered (${renderedFps}fps), " +
                "fed=$fed (${fedFps}fps), dropped=$dropped, skipped=$skipped, " +
                "inputWait=${inputWait}ms, codec=$currentCodecName"
        )
    }

    /**
     * Dedicated thread to pull decoded frames and render them to the surface.
     */
    private fun outputThreadLoop() {
        AppLog.i("Output thread started")
        var consecutiveErrors = 0
        var lastOutputMs = SystemClock.elapsedRealtime()

        while (running) {
            val currentCodec = codec
            val bufferInfo = codecBufferInfo
            if (currentCodec == null || bufferInfo == null) {
                try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                continue
            }

            try {
                val outputIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outputIndex >= 0) {
                    // Catch up to the newest ready frame instead of replaying the backlog. A link
                    // that goes quiet for a few hundred milliseconds delivers what it owed in one
                    // burst; showing every frame of it walks the picture forward in slow motion and
                    // never closes the gap against audio, which does not queue the same way. These
                    // frames are already decoded, so discarding them costs no reference data and
                    // cannot corrupt what follows: the one place load can be shed for free.
                    //
                    // "Another buffer is ready" on its own does NOT mean we are behind.
                    // releaseOutputBuffer(true) hands the frame to the surface and returns without
                    // waiting for vsync, so back-pressure lands a step later, on the codec asking
                    // for a graphic buffer. One decoded frame sitting ahead of the display is
                    // ordinary pipeline depth, and treating that as a backlog would discard half
                    // the frames of a perfectly healthy 60fps stream. So nothing is discarded
                    // until a third frame is ready: at depth two both are rendered back to back
                    // and the display shows whichever it can, and only a genuine burst - the one
                    // that arrives after silence, dozens deep - reaches the discard path.
                    readyIndices[0] = outputIndex
                    var readyCount = 1
                    var passes = 0
                    while (readyCount <= MAX_CATCHUP_SKIPS && passes < MAX_CATCHUP_SKIPS * 2) {
                        passes++
                        val readyIndex = currentCodec.dequeueOutputBuffer(bufferInfo, 0L)
                        if (readyIndex >= 0) {
                            readyIndices[readyCount++] = readyIndex
                        } else if (readyIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            handleOutputFormatChange(currentCodec.outputFormat)
                        } else {
                            break
                        }
                    }

                    var alsoRendered = 0
                    if (readyCount > 2) {
                        for (i in 0 until readyCount - 1) {
                            currentCodec.releaseOutputBuffer(readyIndices[i], false)
                        }
                        framesSkippedAtRender += readyCount - 1
                    } else {
                        for (i in 0 until readyCount - 1) {
                            currentCodec.releaseOutputBuffer(readyIndices[i], true)
                            alsoRendered++
                        }
                    }
                    // These went to the surface too, so they belong in the rendered rate; counting
                    // only the last one would understate it by half whenever the pipeline sits one
                    // frame deep, which is the healthy case this is careful not to disturb.
                    framesRendered += alsoRendered
                    frameCount += alsoRendered
                    framesRenderedThisSession += alsoRendered
                    val renderIndex = readyIndices[readyCount - 1]

                    currentCodec.releaseOutputBuffer(renderIndex, true)
                    lastFrameRenderedMs = SystemClock.elapsedRealtime()
                    lastOutputMs = lastFrameRenderedMs
                    framesRenderedThisSession++
                    consecutiveErrors = 0
                    // The one landmark that says video actually reached the screen on the path
                    // almost every unit runs. Driven by its own flag rather than the listener
                    // below, which only exists while the projection activity is up — the sessions
                    // worth timing are exactly the ones where it might not be.
                    if (!loggedFirstHardwareFrame) {
                        loggedFirstHardwareFrame = true
                        AppLog.i("First frame rendered (hardware decode)")
                    }
                    onFirstFrameListener?.let { it(); onFirstFrameListener = null }

                    frameCount++
                    framesRendered++

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsLogTime
                    if (elapsed >= 1000) {
                        if (lastFpsLogTime != 0L) {
                            val fps = (frameCount * 1000 / elapsed).toInt()
                            onFpsChanged?.invoke(fps)
                        }
                        frameCount = 0
                        lastFpsLogTime = now
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    handleOutputFormatChange(currentCodec.outputFormat)
                }

                logThroughput()

                // Stall detection: if we rendered at least one frame but haven't
                // produced output in SYNC_STALL_THRESHOLD_MS, check if input bytes
                // are still arriving from the phone. If no input bytes arrived recently,
                // Android Auto has simply paused the stream (idle/static screen), so
                // we update lastOutputMs to prevent false-positive restarts and screen flickering.
                val now = SystemClock.elapsedRealtime()
                val stallGap = now - lastOutputMs
                if (stallGap > SYNC_STALL_THRESHOLD_MS) {
                    val inputIdleGap = now - lastInputBytesReceivedMs
                    if (inputIdleGap > SYNC_STALL_THRESHOLD_MS) {
                        // Stream is idle on the phone side (no new video frames arriving).
                        lastOutputMs = now
                    } else {
                        // Input bytes ARE arriving, but decoder produces no output -> REAL DECODER STALL!
                        // A device that is merely marginal — renders fine for stretches, then
                        // stalls under load — never trips restartsSinceLastFrame's cap, since that
                        // counts only restarts where no frame at all was rendered. Cap and cooldown
                        // this watchdog the same way rather than rebuilding the MediaCodec every
                        // time it fires.
                        if (syncStallRestartCount > 0 && now - lastSyncStallRestartMs > SYNC_STALL_RESET_MS) {
                            syncStallRestartCount = 0
                        }
                        if (now - lastSyncStallRestartMs >= SYNC_STALL_COOLDOWN_MS &&
                            syncStallRestartCount < MAX_SYNC_STALL_RESTARTS) {
                            syncStallRestartCount++
                            lastSyncStallRestartMs = now
                            AppLog.w("Decoder stall detected (no output for ${stallGap}ms while receiving input). Forcing restart ($syncStallRestartCount/$MAX_SYNC_STALL_RESTARTS).")
                            scheduleRestart("sync_stall")
                            break
                        }
                    }
                    // Suppressed by the cooldown or the cap. Report it, throttled: the branch
                    // above is the only thing that ever mentions a stall, so once it stops
                    // firing a decoder that has exhausted its restart budget keeps stalling
                    // with an entirely clean log and reads as healthy.
                    if (now - lastSyncStallSuppressedLogMs >= SYNC_STALL_SUPPRESSED_LOG_INTERVAL_MS) {
                        lastSyncStallSuppressedLogMs = now
                        AppLog.w("Decoder stall detected (no output for ${stallGap}ms) but restart suppressed ($syncStallRestartCount/$MAX_SYNC_STALL_RESTARTS used, ${SYNC_STALL_COOLDOWN_MS}ms cooldown). Still spinning on output.")
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    consecutiveErrors++
                    AppLog.w("Codec exception in output thread (attempt $consecutiveErrors): ${e.message}")
                    if (consecutiveErrors >= 3) {
                        AppLog.e("Too many consecutive exceptions in output thread. Forcing restart.")
                        scheduleRestart("sync_consecutive_errors")
                        break
                    }
                    try { Thread.sleep(50) } catch (ignore: Exception) {}
                }
            }
        }
        AppLog.i("Output thread stopped")
    }

    /**
     * Resolves the best available hardware or software decoder for the given mime type.
     */
    private fun findBestCodec(mimeType: String, preferHardware: Boolean): String? {
        val codecInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        } else {
            @Suppress("DEPRECATION")
            val count = MediaCodecList.getCodecCount()
            (0 until count).map { MediaCodecList.getCodecInfoAt(it) }
        }

        val infos = codecInfos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, true) } }
        val hw = infos.find { isHardwareAccelerated(it) }
        val sw = infos.find { !isHardwareAccelerated(it) }
        val selected = if (preferHardware && hw != null) hw.name else sw?.name ?: hw?.name
        AppLog.i("findBestCodec: hw=${hw?.name}, sw=${sw?.name}, preferHardware=$preferHardware, selected=$selected")
        return selected
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val lower = info.name.lowercase(Locale.ROOT)
        return !(lower.startsWith("omx.google.") || lower.startsWith("c2.android.") ||
                lower.startsWith("omx.ffmpeg.") || lower.contains(".sw.") || lower.contains("software"))
    }
}

/**
 * Helper to parse Bitstreams for SPS data.
 */
private class BitReader(private val buffer: ByteArray, private val offset: Int, private val size: Int) {
    private var bitPosition = offset * 8
    private val bitLimit = (offset + size) * 8

    fun readBit(): Int {
        if (bitPosition >= bitLimit) return 0
        return (buffer[bitPosition / 8].toInt() shr (7 - (bitPosition++ % 8))) and 1
    }

    fun readBits(count: Int): Int {
        var res = 0
        repeat(count) { res = (res shl 1) or readBit() }
        return res
    }

    fun readUE(): Int {
        var zeros = 0
        while (readBit() == 0 && bitPosition < bitLimit) zeros++
        return if (zeros == 0) 0 else (1 shl zeros) - 1 + readBits(zeros)
    }
}

data class SpsData(val width: Int, val height: Int)

/**
 * Parses AVC/H.264 Sequence Parameter Sets to extract video dimensions.
 */
private object SpsParser {
    fun parse(sps: ByteArray, offset: Int, size: Int): SpsData? {
        try {
            val reader = BitReader(sps, offset, size)
            reader.readBits(8)
            val profileIdc = reader.readBits(8)
            reader.readBits(16)
            reader.readUE()
            if (profileIdc in listOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
                val chroma = reader.readUE()
                if (chroma == 3) reader.readBit()
                reader.readUE(); reader.readUE(); reader.readBit()
                if (reader.readBit() == 1) {
                    repeat(if (chroma != 3) 8 else 12) {
                        if (reader.readBit() == 1) {
                            var last = 8; var next = 8
                            repeat(if (it < 6) 16 else 64) {
                                if (next != 0) next = (last + reader.readUE() + 256) % 256
                                if (next != 0) last = next
                            }
                        }
                    }
                }
            }
            reader.readUE()
            if (reader.readUE() == 0) reader.readUE()
            reader.readUE(); reader.readBit()
            val w = (reader.readUE() + 1) * 16
            val hMap = reader.readUE()
            val mbs = reader.readBit()
            var h = (2 - mbs) * (hMap + 1) * 16
            if (mbs == 0) reader.readBit()
            reader.readBit()
            if (reader.readBit() == 1) {
                val l = reader.readUE(); val r = reader.readUE()
                val t = reader.readUE(); val b = reader.readUE()
                return SpsData(w - (l + r) * 2, h - (t + b) * 2)
            }
            return SpsData(w, h)
        } catch (e: Exception) { return null }
    }
}
