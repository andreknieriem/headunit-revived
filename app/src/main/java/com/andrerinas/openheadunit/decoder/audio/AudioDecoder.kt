package com.andrerinas.openheadunit.decoder.audio

import android.util.SparseArray
import com.andrerinas.openheadunit.utils.AppLog

class AudioDecoder {

    private val audioTracks = SparseArray<AudioTrackWrapper>(3)
    private var mixer: AudioMixer? = null

    fun getTrack(channel: Int): AudioTrackWrapper? {
        return audioTracks.get(channel)
    }

    fun getMixer(): AudioMixer? {
        return mixer
    }

    fun hasMixer(): Boolean {
        return mixer != null && mixer!!.isRunning()
    }

    fun decode(channel: Int, buffer: ByteArray, offset: Int, size: Int) {
        val audioTrack = audioTracks.get(channel)
        audioTrack?.write(buffer, offset, size)
    }

    fun stop() {
        for (i in 0 until audioTracks.size()) {
            stop(audioTracks.keyAt(i))
        }
        releaseMixer()
    }

    fun stop(chan: Int) {
        val audioTrack = audioTracks.get(chan)
        audioTrack?.stopPlayback()
        audioTracks.put(chan, null)
    }

    fun releaseMixer() {
        synchronized(this) {
            mixer?.stop()
            mixer = null
        }
    }

    fun start(channel: Int, stream: Int, sampleRate: Int, numberOfBits: Int, numberOfChannels: Int, isAac: Boolean = false, gain: Float = 1.0f, audioLatencyMultiplier: Int = 8, audioQueueCapacity: Int = 0, staticAudioFocus: Boolean = false, attachHwDspEqualizer: Boolean = false) {
        if (staticAudioFocus) {
            synchronized(this) {
                if (mixer == null) {
                    mixer = AudioMixer(stream, attachHwDspEqualizer)
                    mixer!!.start()
                    AppLog.i("AudioDecoder: Created and started shared AudioMixer")
                }
            }
        }
        val thread = AudioTrackWrapper(
            stream = stream,
            sampleRateInHz = sampleRate,
            bitDepth = numberOfBits,
            channelCount = numberOfChannels,
            isAac = isAac,
            gain = gain,
            audioLatencyMultiplier = audioLatencyMultiplier,
            audioQueueCapacity = audioQueueCapacity,
            mixer = if (staticAudioFocus) mixer else null,
            channelId = channel,
            attachHwDspEqualizer = attachHwDspEqualizer
        )
        audioTracks.put(channel, thread)
    }

    fun setGain(channel: Int, gain: Float) {
        audioTracks.get(channel)?.setGain(gain)
    }

    companion object {
        const val SAMPLE_RATE_HZ_48 = 48000
        const val SAMPLE_RATE_HZ_16 = 16000
    }
}
