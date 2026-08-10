package com.andrerinas.openheadunit.aap

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.AudioConfigs
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.decoder.AudioDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.Settings

internal class AapAudio(
        private val audioDecoder: AudioDecoder,
        private val audioManager: AudioManager,
        private val settings: Settings,
        private val context: Context) {

    private val staticAudioFocus = settings.staticAudioFocus
    private val separateAudioStreams = settings.separateAudioStreams
    private val mediaVolumeOffset = settings.mediaVolumeOffset
    private val assistantVolumeOffset = settings.assistantVolumeOffset
    private val navigationVolumeOffset = settings.navigationVolumeOffset
    private val audioLatencyMultiplier = settings.audioLatencyMultiplier
    private val useAacAudio = settings.useAacAudio
    private val audioQueueCapacity = settings.audioQueueCapacity
    private val enableAudioSink = settings.enableAudioSink
    private val attachHwDspEqualizer = settings.attachHwDspEqualizer
    private val playbackFocusMode = settings.playbackFocusMode

    private var audioFocusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    @Volatile
    private var playbackFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var legacyPlaybackFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    // Dynamic-mode playback focus: some phones never send an AudioFocusRequestNotification(GAIN)
    // when media starts (the always-grant handshake makes them assume the head unit always holds
    // focus), so relying on the protocol notification alone leaves the car radio playing alongside
    // AA audio. Instead we hold system audio focus for as long as any AA audio channel is actively
    // playing and release it when the last one stops. Static mode manages focus permanently and is
    // left untouched.
    //
    // Whether we take it at all is PlaybackFocusPolicy's call: on a head unit that is also the
    // phone's Bluetooth A2DP sink, the focus grab makes the sink service AVRCP-pause that same
    // phone, silencing the stream we are playing.
    private val activeAudioChannels = mutableSetOf<Int>()
    private val playbackFocusListener = AudioManager.OnAudioFocusChangeListener {
        AppLog.i("AapAudio: playback audio focus changed: $it")
    }

    @Volatile
    private var holdingPlaybackFocus = false
    @Volatile
    private var focusAcquiredAtMs = 0L
    @Volatile
    private var selfDefeatingStops = 0
    @Volatile
    private var selfDefeatingLatched = false

    @Volatile
    private var isDucked = false
    private val handler = Handler(Looper.getMainLooper())
    private val unduckRunnable = Runnable {
        unduckMedia()
    }

    private fun duckMedia() {
        if (!isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val duckedVolume = getMediaGain() * DUCK_VOLUME_FACTOR
                AppLog.i("Static Audio Focus: Ducking media volume to $duckedVolume")
                mediaTrack.setVolume(duckedVolume)
                isDucked = true
            }
        }
    }

    private fun unduckMedia() {
        if (isDucked) {
            val mediaTrack = audioDecoder.getTrack(Channel.ID_AUD)
            if (mediaTrack != null) {
                val originalVolume = getMediaGain()
                AppLog.i("Static Audio Focus: Restoring media volume to $originalVolume")
                mediaTrack.setVolume(originalVolume)
            }
            isDucked = false
        }
    }

    private fun getMediaGain(): Float {
        return (1.0f + (mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
    }

    /**
     * Whether a focus request the phone asked for over the protocol should reach the system.
     *
     * [AapControl] answers every AudioFocusRequestNotification with an always-grant reply, which is
     * what keeps AA routing audio to us; separately it asks the system for the focus the phone
     * wanted, so other apps on the head unit duck. That second half is the same act that makes an
     * A2DP sink AVRCP-pause the phone we project, and it honours GAIN as a *permanent* grab, so it
     * needs the same rule as the playback path.
     *
     * RELEASE is never gated: abandoning focus must always work, or a grab from before the link
     * came up would be stranded for the rest of the session.
     *
     * The latch does not extend here — it is armed in [onAudioPlaybackStopped], which only runs
     * while the playback path holds focus. On a unit whose Bluetooth probe reads nothing, this path
     * has no automatic backstop and needs the mode set to NEVER by hand.
     */
    fun shouldHonourProtocolFocusRequest(isRelease: Boolean): Boolean {
        if (isRelease) return true

        val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(context)
        // isAudioChannel: the notification arrives on the control channel, but the question being
        // asked is about audio focus. The flag means "this is an audio-focus decision", not "this
        // message came in on an audio channel".
        val honour = PlaybackFocusPolicy.shouldAcquire(
                mode = playbackFocusMode,
                staticAudioFocus = staticAudioFocus,
                audioSinkEnabled = enableAudioSink,
                isAudioChannel = true,
                btMediaLinkActive = btMediaLinkActive,
                selfDefeatingLatched = selfDefeatingLatched)

        if (!honour) {
            AppLog.i("AapAudio: phone asked for audio focus - leaving system audio focus alone " +
                    "(mode=$playbackFocusMode, bluetoothMedia=$btMediaLinkActive, latched=$selfDefeatingLatched)")
        }
        return honour
    }

    fun requestFocusChange(stream: Int, focusRequest: Int, callback: AudioManager.OnAudioFocusChangeListener): Int {
        AppLog.i("Audio Focus Request: stream=$stream, type=$focusRequest")
        
        var result = AudioManager.AUDIOFOCUS_REQUEST_FAILED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+
            if (focusRequest == Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE) {
                AppLog.i("Releasing audio focus")
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
                result = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                val usage = when (stream) {
                    AudioManager.STREAM_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                    AudioManager.STREAM_VOICE_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else -> AudioAttributes.USAGE_MEDIA
                }

                val audioAttributes = AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(if (usage == AudioAttributes.USAGE_MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                audioFocusRequest = AudioFocusRequest.Builder(focusRequest)
                        .setAudioAttributes(audioAttributes)
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(callback)
                        .build()
                
                result = audioManager.requestAudioFocus(audioFocusRequest!!)
                AppLog.i("Audio focus request result: ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED ($result)"}")
            }
        } else { // API < 26
            @Suppress("DEPRECATION")
            result = when (focusRequest) {
                Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE -> {
                    audioManager.abandonAudioFocus(callback)
                    legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
                    legacyFocusListener = null
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_VALUE -> {
                    legacyFocusListener = callback
                    audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_VALUE -> {
                    legacyFocusListener = callback
                    audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK_VALUE -> {
                    legacyFocusListener = callback
                    audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                }
                else -> AudioManager.AUDIOFOCUS_REQUEST_FAILED
            }
            AppLog.i("Audio focus request result (legacy): ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED"}")
        }
        return result
    }

    private fun requestPlaybackFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(playbackFocusListener)
                    .build()
            playbackFocusRequest = request
            
            val result = audioManager.requestAudioFocus(request)
            AppLog.i("AapAudio: Playback transient focus request result: ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED ($result)"}")
        } else {
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener = playbackFocusListener
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(playbackFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            AppLog.i("AapAudio: Playback transient focus request result (legacy): ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "FAILED"}")
        }
    }

    private fun releasePlaybackFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackFocusRequest?.let { 
                AppLog.i("AapAudio: Releasing playback transient audio focus")
                audioManager.abandonAudioFocusRequest(it) 
            }
            playbackFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener?.let { 
                AppLog.i("AapAudio: Releasing playback transient audio focus (legacy)")
                audioManager.abandonAudioFocus(it) 
            }
            legacyPlaybackFocusListener = null
        }
    }

    fun releaseAllFocus() {
        AppLog.i("AapAudio: Releasing all audio focus.")
        synchronized(activeAudioChannels) { activeAudioChannels.clear() }
        // The latch is a property of one connection, not of the head unit: re-arm it so a session
        // that reconnects with Bluetooth off gets the car-radio behaviour back.
        holdingPlaybackFocus = false
        focusAcquiredAtMs = 0L
        selfDefeatingStops = 0
        selfDefeatingLatched = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
            playbackFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            playbackFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
            legacyFocusListener = null
            @Suppress("DEPRECATION")
            legacyPlaybackFocusListener?.let { audioManager.abandonAudioFocus(it) }
            legacyPlaybackFocusListener = null
        }
    }

    /**
     * Processes a message as an audio stream packet.
     * Returns true if the packet was identified and processed as audio data, false otherwise.
     */
    fun process(message: AapMessage): Boolean {
        // Media stream packets have msgType 0 or 1.
        // Control packets on audio channels (Setup, Start, Stop) have types > 32767.
        if (message.type == 0 || message.type == 1) {
            if (message.size >= 10) {
                decode(message.channel, 10, message.data, message.size - 10)
            }
            return true
        }
        return false
    }

    private fun startAudioTrack(channel: Int) {
        if (audioDecoder.getTrack(channel) != null) return

        val config = AudioConfigs.get(channel)
        val stream = AudioConfigs.stream(channel, separateAudioStreams)

        val offset = when (channel) {
            Channel.ID_AUD -> mediaVolumeOffset
            Channel.ID_AU1 -> assistantVolumeOffset
            Channel.ID_AU2 -> navigationVolumeOffset
            else -> 0
        }
        val gain = (1.0f + (offset / 100.0f)).coerceIn(0.0f, 2.0f)

        // Voice and Navigation benefit from lower latency. Cap the multiplier for those channels.
        val effectiveMultiplier = if (channel == Channel.ID_AUD) {
            audioLatencyMultiplier
        } else {
            audioLatencyMultiplier.coerceAtMost(4)
        }

        AppLog.i("AudioDecoder.start: channel=$channel, stream=$stream, gain=$gain, sampleRate=${config.sampleRate}, numberOfBits=${config.numberOfBits}, numberOfChannels=${config.numberOfChannels}, isAac=$useAacAudio, latencyMultiplier=$effectiveMultiplier, queueCapacity=$audioQueueCapacity, attachHwDspEqualizer=$attachHwDspEqualizer")
        audioDecoder.start(channel, stream, config.sampleRate, config.numberOfBits, config.numberOfChannels, useAacAudio, gain, effectiveMultiplier, audioQueueCapacity, staticAudioFocus, attachHwDspEqualizer)
        onAudioPlaybackStarted(channel)
    }

    private fun onAudioPlaybackStarted(channel: Int) {
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return

        // Only the set mutation belongs under the lock. requestPlaybackFocus() is a binder
        // round-trip to AudioService and the Bluetooth probe below is another, and this runs on
        // the transport thread for every track change.
        val wasEmpty = synchronized(activeAudioChannels) {
            val empty = activeAudioChannels.isEmpty()
            activeAudioChannels.add(channel)
            empty
        }
        if (!wasEmpty) return

        val btMediaLinkActive = BluetoothHelper.isA2dpMediaLinkActive(context)
        val acquire = PlaybackFocusPolicy.shouldAcquire(
                mode = playbackFocusMode,
                staticAudioFocus = staticAudioFocus,
                audioSinkEnabled = enableAudioSink,
                isAudioChannel = true,
                btMediaLinkActive = btMediaLinkActive,
                selfDefeatingLatched = selfDefeatingLatched)

        if (!acquire) {
            AppLog.i("AapAudio: AA audio started (${Channel.name(channel)}) - leaving system audio focus alone " +
                    "(mode=$playbackFocusMode, bluetoothMedia=$btMediaLinkActive, latched=$selfDefeatingLatched)")
            return
        }

        // Use GAIN_TRANSIENT, not GAIN: a permanent GAIN sends other players (e.g. the car
        // radio) a permanent AUDIOFOCUS_LOSS, so they stop and do NOT resume when we later
        // abandon focus. TRANSIENT sends AUDIOFOCUS_LOSS_TRANSIENT so they pause and resume
        // once AA audio stops and we release focus.
        AppLog.i("AapAudio: AA audio started (${Channel.name(channel)}) - acquiring transient system audio focus " +
                "(mode=$playbackFocusMode, bluetoothMedia=$btMediaLinkActive)")
        focusAcquiredAtMs = SystemClock.elapsedRealtime()
        holdingPlaybackFocus = true
        requestPlaybackFocus()
    }

    /**
     * Releases system audio focus once the last active AA audio channel stops (dynamic mode),
     * letting other local sources (e.g. the car radio) resume.
     */
    private fun onAudioPlaybackStopped(channel: Int) {
        if (staticAudioFocus || !enableAudioSink || !Channel.isAudio(channel)) return

        val nowEmpty = synchronized(activeAudioChannels) {
            activeAudioChannels.remove(channel)
            activeAudioChannels.isEmpty()
        }
        if (!nowEmpty || !holdingPlaybackFocus) return

        noteStopWhileHoldingFocus(channel)

        AppLog.i("AapAudio: last AA audio channel stopped - releasing transient system audio focus")
        holdingPlaybackFocus = false
        releasePlaybackFocus()
    }

    /**
     * Watches for the pathology the Bluetooth probe is meant to pre-empt, for the units where it
     * cannot see the link: the media channel closing again almost as soon as we took focus, because
     * the phone stopped its own playback in response. Two of those in a row and we stop asking for
     * focus, so the session settles instead of cycling every few seconds.
     */
    private fun noteStopWhileHoldingFocus(channel: Int) {
        if (selfDefeatingLatched || focusAcquiredAtMs == 0L) return

        val elapsedMs = SystemClock.elapsedRealtime() - focusAcquiredAtMs
        if (!PlaybackFocusPolicy.countsAsSelfDefeating(channel == Channel.ID_AUD, elapsedMs)) {
            selfDefeatingStops = 0
            return
        }

        selfDefeatingStops++
        AppLog.d("AapAudio: media stopped ${elapsedMs}ms after taking audio focus " +
                "($selfDefeatingStops/${PlaybackFocusPolicy.SELF_DEFEATING_LIMIT})")
        if (selfDefeatingStops >= PlaybackFocusPolicy.SELF_DEFEATING_LIMIT) {
            selfDefeatingLatched = true
            AppLog.w("AapAudio: taking system audio focus is stopping the phone's own playback " +
                    "(the head unit is most likely its Bluetooth audio sink) - not acquiring it again this session")
        }
    }

    fun precreateAudioTrack(channel: Int) {
        if (!staticAudioFocus) return
        if (channel != Channel.ID_AU2) return
        startAudioTrack(channel)
    }

    private fun decode(channel: Int, start: Int, buf: ByteArray, len: Int) {
        var length = len
        if (length > AUDIO_BUFS_SIZE) {
            AppLog.e("Error audio len: %d  aud_buf_BUFS_SIZE: %d", length, AUDIO_BUFS_SIZE)
            length = AUDIO_BUFS_SIZE
        }

        if (audioDecoder.getTrack(channel) == null) {
            startAudioTrack(channel)
        }

        audioDecoder.decode(channel, buf, start, length)

        if ((channel == Channel.ID_AU1 || channel == Channel.ID_AU2) && staticAudioFocus) {
            duckMedia()
            handler.removeCallbacks(unduckRunnable)
            handler.postDelayed(unduckRunnable, UNDUCK_DELAY_MS)
        }
    }

    fun updateGains() {
        val mediaGain = (1.0f + (settings.mediaVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
        val assistantGain = (1.0f + (settings.assistantVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)
        val navGain = (1.0f + (settings.navigationVolumeOffset / 100.0f)).coerceIn(0.0f, 2.0f)

        audioDecoder.setGain(Channel.ID_AUD, mediaGain)
        audioDecoder.setGain(Channel.ID_AU1, assistantGain)
        audioDecoder.setGain(Channel.ID_AU2, navGain)
    }

    fun restartAudio() {
        AppLog.i("AapAudio: Restarting all audio tracks")
        audioDecoder.stop()
    }

    fun stopAudio(channel: Int) {
        AppLog.i("Audio Stop: " + Channel.name(channel))
        if ((channel == Channel.ID_AU1 || channel == Channel.ID_AU2) && staticAudioFocus) {
            // Keep the speech wrappers alive to prevent recreate overhead and keep state consistent.
            // Just restore media volume.
            handler.removeCallbacks(unduckRunnable)
            unduckMedia()
        } else {
            audioDecoder.stop(channel)
            onAudioPlaybackStopped(channel)
        }
    }

    companion object {
        private const val AUDIO_BUFS_SIZE = 65536 * 4  // Up to 256 Kbytes
        private const val DUCK_VOLUME_FACTOR = 0.4f
        private const val UNDUCK_DELAY_MS = 1500L
    }
}
