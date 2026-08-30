package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioTrackWrapper(
    stream: Int,
    sampleRateInHz: Int,
    bitDepth: Int,
    channelCount: Int,
    private val isAac: Boolean = false,
    gain: Float,
    private val audioLatencyMultiplier: Int = 8,
    private val audioQueueCapacity: Int = 0,
    private val mixer: AudioMixer? = null,
    private val channelId: Int = -1,
    private val attachHwDspEqualizer: Boolean = false
) : Thread() {

    private data class AudioChunk(
        val data: ByteArray,
        val size: Int
    )

    companion object {
        private const val AUDIO_BUFFER_POOL_LIMIT = 16
        private const val MIN_POOLED_AUDIO_BUFFER_SIZE = 4096

        /**
         * Ceiling on the computed drain wait.
         *
         * The wait is normally a couple of hundred milliseconds. The cap is for when the frame
         * accounting is off, so a bad subtraction costs a beat rather than a hang.
         */
        private const val DRAIN_CAP_MS = 1_000L
    }

    private val audioTrack: AudioTrack?
    private var decoder: MediaCodec? = null
    private var codecHandlerThread: HandlerThread? = null
    private val freeInputBuffers = LinkedBlockingQueue<Int>()
    private val writeExecutor = Executors.newSingleThreadExecutor()
    private val writeSemaphore = java.util.concurrent.Semaphore(3)
    private var equalizer: Equalizer? = null

    // Limit queue capacity to provide backpressure to the network thread if audio playback is slow
    private val dataQueue = if (audioQueueCapacity > 0)
        LinkedBlockingQueue<AudioChunk>(audioQueueCapacity)
    else
        LinkedBlockingQueue<AudioChunk>()
    private val audioBufferPool = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var isRunning = true

    @Volatile
    private var currentGain: Float = gain

    fun setVolume(gain: Float) {
        currentGain = gain
        val track = audioTrack
        if (track != null) {
            try {
                val hwGain = gain.coerceAtMost(1.0f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track.setVolume(hwGain)
                } else {
                    @Suppress("DEPRECATION")
                    track.setStereoVolume(hwGain, hwGain)
                }
            } catch (e: Exception) {
                AppLog.e("Failed to set volume on AudioTrack", e)
            }
        } else {
            mixer?.setChannelGain(channelId, gain)
        }
    }

    private fun applyGain(buffer: ByteArray, size: Int) {
        if (currentGain <= 1.0f) return
        for (i in 0 until size - 1 step 2) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt() // High byte handles sign
            val sample = (high shl 8) or low
            val modifiedSample = (sample * currentGain).toInt().coerceIn(-32768, 32767)
            buffer[i] = (modifiedSample and 0xFF).toByte()
            buffer[i + 1] = (modifiedSample shr 8).toByte()
        }
    }

    // Frames written, for the pre-roll trigger and the drain wait. Volatile because the AAC path
    // advances it from the write executor while the playback thread reads it, and a long is not
    // read atomically on 32-bit ABIs. Only one thread per instance increments it, so visibility is
    // the only hazard.
    @Volatile
    private var framesWritten: Long = 0
    private val bytesPerFrame: Int = channelCount * (if (bitDepth == 16) 2 else 1)
    private val sampleRate: Int = sampleRateInHz

    /** Byte size handed to the AudioTrack, recorded by [createAudioTrack] for the pre-roll target. */
    private var trackBufferBytes: Int = 0

    /** Frames to bank before [android.media.AudioTrack.play]. See [AudioPrerollPolicy]. */
    private var prerollTargetFrames: Int = 1

    /**
     * When audio first reached this track, for the pre-roll deadline.
     *
     * Stamped on the first write rather than at construction: a track precreated at Media Sink
     * Setup can sit idle for minutes, and a deadline measured from then has already expired when
     * the first chunk arrives.
     */
    @Volatile
    private var firstAudioMs: Long = 0L

    private val playbackStarted = AtomicBoolean(false)

    init {
        this.name = "AudioPlaybackThread"
        audioTrack = if (mixer == null) {
            createAudioTrack(stream, sampleRateInHz, bitDepth, channelCount, audioLatencyMultiplier)
        } else {
            null
        }

        if (mixer != null) {
            mixer.registerChannel(channelId, sampleRateInHz, channelCount)
            mixer.setChannelGain(channelId, gain)
        } else {
            setVolume(gain)
            audioTrack?.let { track ->
                attachHwDspEqualizerQuietly(track.audioSessionId)
                // Deliberately no play() here: started empty, the track underran on every media
                // start. [AudioPrerollPolicy] decides when enough is banked to begin.
                prerollTargetFrames =
                    AudioPrerollPolicy.targetFrames(sampleRateInHz, trackCapacityFrames(track))
            }
        }

        if (isAac) {
            initDecoder(sampleRateInHz, channelCount)
        }

        this.start()
    }

    private fun initDecoder(sampleRate: Int, channels: Int) {
        try {
            val mime = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            // CSD for RAW AAC-LC (AudioSpecificConfig)
            val csd = makeAacCsd(sampleRate, channels)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))

            decoder = MediaCodec.createDecoderByType(mime)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Use a HandlerThread for the codec callback but set its priority to AUDIO
                // to prevent it from being starved by the video decoder.
                codecHandlerThread = object : HandlerThread("AacCodecThread") {
                    override fun onLooperPrepared() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    }
                }
                codecHandlerThread!!.start()

                val callback = object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        freeInputBuffers.offer(index)
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        try {
                            if (!isRunning) return
                            val outputBuffer = codec.getOutputBuffer(index)
                            if (outputBuffer != null && info.size > 0) {
                                val chunk = ByteArray(info.size)
                                outputBuffer.position(info.offset)
                                outputBuffer.get(chunk)
                                outputBuffer.clear()

                                writeSemaphore.acquire()
                                // Write to AudioTrack or Mixer using executor
                                writeExecutor.submit {
                                    try {
                                        writeToTrack(chunk)
                                    } catch (e: Exception) {
                                        AppLog.e("Error writing decoded AAC to AudioTrack", e)
                                    } finally {
                                        writeSemaphore.release()
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                        } catch (e: Exception) {
                            AppLog.e("Error processing AAC output", e)
                            if (e is InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        AppLog.e("AAC Codec Error", e)
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        AppLog.i("AAC Output Format Changed: $format")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val handler = Handler(codecHandlerThread!!.looper)
                    decoder!!.setCallback(callback, handler)
                } else {
                    decoder!!.setCallback(callback)
                }
            }

            decoder?.configure(format, null, null, 0)
            decoder?.start()
            AppLog.i("AAC Decoder started for $sampleRate Hz, $channels channels (Async)")
        } catch (e: Exception) {
            AppLog.e("Failed to init AAC decoder", e)
        }
    }

    private fun writeToTrack(buffer: ByteArray) {
        writeToTrack(buffer, buffer.size)
    }

    private fun writeToTrack(buffer: ByteArray, size: Int) {
        if (mixer != null) {
            mixer.feed(channelId, buffer, 0, size)
            framesWritten += size / bytesPerFrame
        } else {
            applyGain(buffer, size)
            // Before the write, on whichever thread makes it: write() on a track that is not
            // playing blocks until only play() can make room, so a check after it is too late.
            maybeStartPlayback(size / bytesPerFrame)
            val result = audioTrack?.write(buffer, 0, size) ?: 0
            if (result > 0) {
                framesWritten += result / bytesPerFrame
            }
        }
    }

    /**
     * Starts the track once [AudioPrerollPolicy] says enough is banked.
     *
     * [writeToTrack] calls it in front of every write, which is where an ordinary stream starts;
     * the run loop calls it once a pass, which is what plays a stream too short to reach its
     * target. Idempotent because the AAC path writes from the write executor while the run loop
     * turns, so both can arrive at once.
     */
    private fun maybeStartPlayback(framesIncoming: Int) {
        if (playbackStarted.get()) return
        val track = audioTrack ?: return
        val now = SystemClock.elapsedRealtime()
        if (framesIncoming > 0 && firstAudioMs == 0L) firstAudioMs = now
        if (firstAudioMs == 0L) return
        val elapsed = now - firstAudioMs
        if (!AudioPrerollPolicy.shouldStart(
                framesWritten,
                framesIncoming,
                prerollTargetFrames,
                elapsed
            )
        ) return

        if (!playbackStarted.compareAndSet(false, true)) return
        try {
            track.play()
            AppLog.i(
                "AudioTrackWrapper: playback started with $framesWritten frames banked " +
                    "(target $prerollTargetFrames) after ${elapsed}ms"
            )
        } catch (e: Exception) {
            AppLog.e("Failed to start AudioTrack playback", e)
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // Drain the queue even after isRunning is set to false
        while (isRunning || dataQueue.isNotEmpty()) {
            try {
                // Use poll to avoid blocking indefinitely if isRunning becomes false
                val chunk = dataQueue.poll(200, TimeUnit.MILLISECONDS)
                // The fill trigger lives in writeToTrack; this call carries only the deadline, so
                // a stream too short to reach its target still gets played.
                maybeStartPlayback(0)
                if (chunk != null) {
                    try {
                        if (isAac && decoder != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                queueInput(chunk.data, chunk.size)
                            } else {
                                decodeSync(chunk.data, chunk.size)
                            }
                        } else {
                            // PCM path - direct write in this high-priority thread
                            writeToTrack(chunk.data, chunk.size)
                        }
                    } finally {
                        recycleAudioBuffer(chunk.data)
                    }
                }
            } catch (e: InterruptedException) {
                drainQueuedAudio()
                break
            } catch (e: Exception) {
                AppLog.e("Error in AudioTrackWrapper run loop", e)
                isRunning = false
            }
        }
        cleanup()
        AppLog.i("AudioTrackWrapper thread finished.")
    }

    @Suppress("DEPRECATION")
    private fun decodeSync(inputData: ByteArray, size: Int) {
        try {
            val dec = this.decoder ?: return
            val inputIndex = dec.dequeueInputBuffer(200000)
            if (inputIndex >= 0) {
                val inputBuffer = dec.inputBuffers[inputIndex]
                inputBuffer.clear()
                inputBuffer.put(inputData, 0, size)
                dec.queueInputBuffer(inputIndex, 0, size, 0, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outputIndex = dec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                val outputBuffer = dec.outputBuffers[outputIndex]
                val chunk = ByteArray(info.size)
                outputBuffer.position(info.offset)
                outputBuffer.get(chunk)
                writeToTrack(chunk)
                dec.releaseOutputBuffer(outputIndex, false)
                outputIndex = dec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            AppLog.e("Error in decodeSync", e)
        }
    }

    @Throws(InterruptedException::class)
    private fun queueInput(inputData: ByteArray, size: Int) {
        try {
            // Wait for input buffer (with timeout to avoid deadlock if codec dies)
            // Restore to 200ms to prevent dropping frames under load
            val inputIndex = freeInputBuffers.poll(200, TimeUnit.MILLISECONDS)

            if (inputIndex != null && inputIndex >= 0) {
                val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    decoder?.getInputBuffer(inputIndex)
                } else {
                    @Suppress("DEPRECATION")
                    decoder?.inputBuffers?.get(inputIndex)
                }

                inputBuffer?.clear()
                inputBuffer?.put(inputData, 0, size)
                decoder?.queueInputBuffer(inputIndex, 0, size, 0, 0)
            } else {
                AppLog.w("AAC Input Buffer timeout (200ms) - dropping frame")
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Error queuing AAC input", e)
        }
    }

    private fun makeAacCsd(sampleRate: Int, channelCount: Int): ByteArray {
        val sampleRateIndex = getFrequencyIndex(sampleRate)
        val audioObjectType = 2 // AAC-LC

        // Correct packing: [AOT:5][FreqIdx:4][ChanCfg:4][...padding:3]
        val config = ((audioObjectType and 0x1F) shl 11) or
                     ((sampleRateIndex and 0x0F) shl 7) or
                     ((channelCount and 0x0F) shl 3)

        return byteArrayOf(
            ((config shr 8) and 0xFF).toByte(),
            (config and 0xFF).toByte()
        )
    }

    private fun getFrequencyIndex(sampleRate: Int): Int {
        return when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000  -> 11
            7350  -> 12
            else  -> 4 // Default 44100
        }
    }

    private fun createAudioTrack(
        stream: Int,
        sampleRateInHz: Int,
        bitDepth: Int,
        channelCount: Int,
        multiplier: Int
    ): AudioTrack {
        val channelConfig =
            if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val dataFormat =
            if (bitDepth == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, dataFormat)
        val bufferSize = if (minBufferSize > 0) minBufferSize * multiplier else minBufferSize

        trackBufferBytes = bufferSize

        AppLog.i("Audio stream: $stream buffer size: $bufferSize (min: $minBufferSize) sampleRateInHz: $sampleRateInHz channelCount: $channelCount")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val (usage, contentType) = when (stream) {
                AudioManager.STREAM_NOTIFICATION -> Pair(
                    AudioAttributes.USAGE_NOTIFICATION,
                    AudioAttributes.CONTENT_TYPE_SONIFICATION
                )
                AudioManager.STREAM_VOICE_CALL -> Pair(
                    AudioAttributes.USAGE_VOICE_COMMUNICATION,
                    AudioAttributes.CONTENT_TYPE_SPEECH
                )
                else -> Pair(
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.CONTENT_TYPE_MUSIC
                )
            }

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(contentType)
                .setLegacyStreamType(stream)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRateInHz)
                .setChannelMask(channelConfig)
                .setEncoding(dataFormat)
                .build()

            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                stream,
                sampleRateInHz,
                channelConfig,
                dataFormat,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
    }

    /**
     * Frames the track can actually hold.
     *
     * `setBufferSizeInBytes()` is a request the framework may clamp, and the pre-roll margin is the
     * only thing keeping a write off a not-yet-playing track from blocking on the thread that would
     * start it. Ask the track where the API allows; fall back to the requested size below M.
     */
    private fun trackCapacityFrames(track: AudioTrack): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val frames = try {
                track.bufferSizeInFrames
            } catch (e: Exception) {
                0
            }
            if (frames > 0) return frames
        }
        return if (bytesPerFrame > 0) trackBufferBytes / bytesPerFrame else 0
    }

    private fun attachHwDspEqualizerQuietly(sessionId: Int) {
        if (!attachHwDspEqualizer) return
        if (sessionId != AudioManager.AUDIO_SESSION_ID_GENERATE && sessionId > 0) {
            try {
                equalizer?.release()
                val eq = Equalizer(0, sessionId)
                eq.enabled = true
                equalizer = eq
                AppLog.i("Attached dummy Equalizer to audioSessionId $sessionId to trigger HW DSP")
            } catch (t: Throwable) {
                // Ignore if Equalizer or AudioEffect is unsupported on device
            }
        }
    }

    fun write(buffer: ByteArray, offset: Int, size: Int) {
        if (!isRunning) return

        var data: ByteArray? = null
        try {
            data = obtainAudioBuffer(size)
            System.arraycopy(buffer, offset, data, 0, size)
            val success = dataQueue.offer(AudioChunk(data, size), 5, TimeUnit.MILLISECONDS)
            if (!success) {
                recycleAudioBuffer(data)
                AppLog.w("Audio queue is full, dropping audio frame to prevent stalling")
            }
        } catch (e: InterruptedException) {
            data?.let { recycleAudioBuffer(it) }
            Thread.currentThread().interrupt()
            AppLog.w("Interrupted while putting audio data to queue")
        }
    }

    fun setGain(gain: Float) {
        AppLog.d("AudioTrackWrapper: updating gain to $gain")
        setVolume(gain)
    }

    fun stopPlayback() {
        isRunning = false
        this.interrupt()
    }

    private fun cleanup() {
        drainQueuedAudio()
        audioBufferPool.clear()

        // 1. Stop the decoder to stop producing new output buffers
        try {
            decoder?.stop()
            decoder?.release()
            decoder = null
        } catch (e: Exception) {
            AppLog.e("Error releasing audio decoder", e)
        }

        // 2. Wait for AAC writes that were already submitted to the executor
        writeExecutor.shutdown()
        try {
            if (!writeExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                AppLog.w("Audio write executor did not terminate in time")
                writeExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            AppLog.w("Audio write executor interrupted during shutdown")
            writeExecutor.shutdownNow()
        }

        if (mixer != null) {
            mixer.unregisterChannel(channelId)
        }

        // 3. stop() plays out what is still buffered on a MODE_STREAM track; this only waits for
        // that to finish before release().
        //
        // It used to wait by polling playbackHeadPosition against framesWritten *after* stop(),
        // and neither exit could fire: stop() zeroes the head, and the stagnation escape was
        // guarded on `pos > 0`. Measured: the full 2500 ms budget on every teardown, long enough
        // to overlap the next track's start.
        //
        // Sampling the head *before* stop() says how much is left to play, so compute the wait.
        val track = audioTrack
        // A track that never reached its pre-roll target still holds everything written to it. It
        // is not playing, so the guard below would skip stop() and release() would discard it -
        // silence where a prompt shorter than the target used to be heard. Start it first.
        if (track != null && !playbackStarted.get() && framesWritten > 0) {
            if (playbackStarted.compareAndSet(false, true)) {
                try {
                    track.play()
                } catch (e: Exception) {
                    AppLog.e("Failed to start AudioTrack for its final drain", e)
                }
            }
        }
        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            var drainMs = 0L
            try {
                val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val pending = (framesWritten - played).coerceAtLeast(0L)
                drainMs = if (sampleRate > 0) {
                    (pending * 1000L / sampleRate).coerceAtMost(DRAIN_CAP_MS)
                } else {
                    0L
                }
                track.stop()
            } catch (e: Exception) {
                AppLog.e("Error stopping audio track", e)
            }

            if (drainMs > 0) {
                try {
                    Thread.sleep(drainMs)
                } catch (e: InterruptedException) {
                    // A restart, not a failure, and what is left in the buffer is stale anyway.
                    // Logged at info: reporters attach these logs, and a stack trace here misled.
                    AppLog.i("AudioTrackWrapper: ${drainMs}ms drain cut short by a restart")
                    Thread.currentThread().interrupt()
                }
            }
        }

        // 4. Release the AudioTrack
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            AppLog.e("Error releasing audio track", e)
        }

        // 5. Clean up the codec handler thread
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                codecHandlerThread?.quitSafely()
            } else {
                codecHandlerThread?.quit()
            }
            codecHandlerThread = null
        } catch (e: Exception) {
            AppLog.e("Error quitting codec thread", e)
        }
    }

    private fun obtainAudioBuffer(size: Int): ByteArray {
        while (true) {
            val pooled = audioBufferPool.poll() ?: return ByteArray(maxOf(size, MIN_POOLED_AUDIO_BUFFER_SIZE))
            if (pooled.size >= size) return pooled
        }
    }

    private fun recycleAudioBuffer(buffer: ByteArray) {
        if (audioBufferPool.size < AUDIO_BUFFER_POOL_LIMIT) {
            audioBufferPool.offer(buffer)
        }
    }

    private fun drainQueuedAudio() {
        while (true) {
            val chunk = dataQueue.poll() ?: break
            recycleAudioBuffer(chunk.data)
        }
    }
}
