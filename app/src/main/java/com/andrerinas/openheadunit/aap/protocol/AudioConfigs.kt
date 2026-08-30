package com.andrerinas.openheadunit.aap.protocol

import android.media.AudioManager
import android.util.SparseArray
import com.andrerinas.openheadunit.aap.protocol.proto.Media

import com.andrerinas.openheadunit.decoder.audio.AudioDecoder

object AudioConfigs {
    private val audioTracks = SparseArray<Media.AudioConfiguration>(3)

    fun stream(channel: Int, separateAudioStreams: Boolean = true) : Int
    {
        if (separateAudioStreams) {
            return when(channel) {
                Channel.ID_AU1 -> AudioManager.STREAM_VOICE_CALL
                Channel.ID_AU2 -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_MUSIC
            }
        }
        return AudioManager.STREAM_MUSIC
    }

    fun get(channel: Int): Media.AudioConfiguration {
        return audioTracks.get(channel)
    }

    init {
        val audioConfig0 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_48
            numberOfBits = 16
            numberOfChannels = 2
        }.build()
        audioTracks.put(Channel.ID_AUD, audioConfig0)

        val audioConfig1 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_16
            numberOfBits = 16
            numberOfChannels = 1
        }.build()
        audioTracks.put(Channel.ID_AU1, audioConfig1)

        // 16 kHz mono is required here, not preferred. The phone accepts the SYSTEM sink only if
        // it offers that config, and with the audio sink off this is the only sink left - so
        // anything else empties its endpoint list and the session ends with "No audio/mic".
        val audioConfig2 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_16
            numberOfBits = 16
            numberOfChannels = 1
        }.build()
        audioTracks.put(Channel.ID_AU2, audioConfig2)
    }
}
