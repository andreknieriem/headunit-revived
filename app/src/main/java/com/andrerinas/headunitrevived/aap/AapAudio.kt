package com.andrerinas.headunitrevived.aap

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock

import com.andrerinas.headunitrevived.aap.protocol.AudioConfigs
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.decoder.AudioDecoder
import com.andrerinas.headunitrevived.utils.AppLog

/**
 * @author algavris
 * *
 * @date 01/10/2016.
 * *
 * *
 * @link https://github.com/google/ExoPlayer/blob/release-v2/library/src/main/java/com/google/android/exoplayer2/audio/AudioTrack.java
 */
internal class AapAudio(
        private val audioDecoder: AudioDecoder,
        private val audioManager: AudioManager) {

    private var audioFocusRequest: AudioFocusRequest? = null

    fun requestFocusChange(stream: Int, focusRequest: Int, callback: AudioManager.OnAudioFocusChangeListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+
            if (focusRequest == Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA) // Use USAGE_MEDIA
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC) // Use CONTENT_TYPE_MUSIC
                        .build()

                audioFocusRequest = AudioFocusRequest.Builder(focusRequest)
                        .setAudioAttributes(audioAttributes)
                        .setOnAudioFocusChangeListener(callback)
                        .build()
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            }
        } else { // API < 26
            @Suppress("DEPRECATION")
            when (focusRequest) {
                Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE_VALUE -> audioManager.abandonAudioFocus(callback)
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_VALUE -> audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN)
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_VALUE -> audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK_VALUE -> audioManager.requestAudioFocus(callback, stream, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        }
    }

    fun process(message: AapMessage): Int {
        if (message.size >= 10) {
            decode(message.channel, 10, message.data, message.size - 10)
        }

        return 0
    }

    private fun decode(channel: Int, start: Int, buf: ByteArray, len: Int) {
        val timestamp = SystemClock.elapsedRealtime()
        AppLog.d("AapAudio: decode - channel=%d, len=%d, ts=%d", channel, len, timestamp)
        var length = len
        if (length > AUDIO_BUFS_SIZE) {
            AppLog.e("Error audio len: %d  aud_buf_BUFS_SIZE: %d", length, AUDIO_BUFS_SIZE)
            length = AUDIO_BUFS_SIZE
        }

        if (audioDecoder.getTrack(channel) == null) {
            val config = AudioConfigs.get(channel)
            val stream = AudioManager.STREAM_MUSIC
            AppLog.i("AudioDecoder.start: channel=$channel, stream=$stream, sampleRate=${config.sampleRate}, numberOfBits=${config.numberOfBits}, numberOfChannels=${config.numberOfChannels}")
            audioDecoder.start(channel, stream, config.sampleRate, config.numberOfBits, config.numberOfChannels)
        }

        audioDecoder.decode(channel, buf, start, length)
    }

    fun stopAudio(channel: Int) {
        AppLog.i("Audio Stop: " + Channel.name(channel))
        audioDecoder.stop(channel)
    }

    companion object {
        private const val AUDIO_BUFS_SIZE = 65536 * 4  // Up to 256 Kbytes
    }
}

