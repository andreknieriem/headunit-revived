package com.andrerinas.headunitrevived.aap.protocol

import android.media.AudioManager
import android.util.SparseArray
import com.andrerinas.headunitrevived.aap.protocol.proto.Media

import com.andrerinas.headunitrevived.decoder.AudioDecoder

object AudioConfigs {
    private val audioTracks = SparseArray<Media.AudioConfiguration>(3)

    fun stream(channel: Int) : Int
    {
        return when(channel) {
            else -> AudioManager.STREAM_MUSIC
        }
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

        val audioConfig2 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_16
            numberOfBits = 16
            numberOfChannels = 1
        }.build()
        audioTracks.put(Channel.ID_AU2, audioConfig2)
    }
}
