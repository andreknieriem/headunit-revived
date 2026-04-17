package com.andrerinas.headunitrevived.decoder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

class MicRecorder(private val micSampleRate: Int, private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private val settings = Settings(context)

    private val micBufferSize: Int
    private var micAudioBuf: ByteArray

    // Indicates whether mic recording is available on this device
    val isAvailable: Boolean

    init {
        val minSize = AudioRecord.getMinBufferSize(micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) {
            // Device doesn't support the requested audio config (common on API 16)
            AppLog.w("MicRecorder: getMinBufferSize returned $minSize, mic recording unavailable")
            micBufferSize = 0
            micAudioBuf = ByteArray(0)
            isAvailable = false
        } else {
            micBufferSize = minSize
            micAudioBuf = ByteArray(minSize)
            isAvailable = true
        }
    }

    private var threadMicAudioActive = false
    private var threadMicAudio: Thread? = null
    var listener: Listener? = null

    // Tracks whether this instance started Bluetooth SCO so we can clean it up
    private var bluetoothScoStarted = false
    private var scoReceiver: BroadcastReceiver? = null

    companion object {
        // Sentinel value stored in settings to indicate Bluetooth SCO mode
        const val SOURCE_BLUETOOTH_SCO = 100
    }

    interface Listener {
        fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int)
    }

    fun stop() {
        AppLog.i("MicRecorder: Stopping. Active: $threadMicAudioActive")
        
        threadMicAudioActive = false
        threadMicAudio?.interrupt()
        threadMicAudio = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                AppLog.e("MicRecorder: Error releasing AudioRecord", e)
            }
        }
        audioRecord = null

        if (bluetoothScoStarted) {
            cleanupSco()
        }
    }

    private fun cleanupSco() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            scoReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {}
        scoReceiver = null
        
        audioManager.stopBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        bluetoothScoStarted = false
        AppLog.i("MicRecorder: Bluetooth SCO stopped")
    }

    private fun micAudioRead(aud_buf: ByteArray, max_len: Int): Int {
        val currentAudioRecord = audioRecord ?: return 0
        val currentListener = listener ?: return 0
        
        val len = currentAudioRecord.read(aud_buf, 0, max_len)
        if (len <= 0) {
            if (len == AudioRecord.ERROR_INVALID_OPERATION && threadMicAudioActive) {
                AppLog.e("MicRecorder: Unexpected interruption error: $len")
            }
            return len
        }

        val resampledData = if (micSampleRate == 16000) {
            aud_buf.sliceArray(0 until len)
        } else {
            resample(aud_buf, len, micSampleRate, 16000)
        }

        currentListener.onMicDataAvailable(resampledData, resampledData.size)
        return len
    }

    private fun resample(data: ByteArray, len: Int, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate) return data.sliceArray(0 until len)

        val inputShorts = ShortArray(len / 2)
        for (i in inputShorts.indices) {
            inputShorts[i] = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xFF)).toShort()
        }

        val outputSize = (inputShorts.size.toLong() * toRate / fromRate).toInt()
        val outputShorts = ShortArray(outputSize)
        val factor = inputShorts.size.toFloat() / outputSize

        for (i in 0 until outputSize) {
            val inputIndex = i * factor
            val i1 = inputIndex.toInt()
            var i2 = i1 + 1
            if (i2 >= inputShorts.size) i2 = inputShorts.size - 1

            val fraction = inputIndex - i1
            val s1 = inputShorts[i1].toInt()
            val s2 = inputShorts[i2].toInt()

            outputShorts[i] = (s1 + (fraction * (s2 - s1))).toInt().toShort()
        }

        val outputBytes = ByteArray(outputSize * 2)
        for (i in outputShorts.indices) {
            val s = outputShorts[i]
            outputBytes[i * 2] = (s.toInt() and 0xFF).toByte()
            outputBytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }

        return outputBytes
    }

    fun start(): Int {
        if (!isAvailable) {
            AppLog.w("MicRecorder: Cannot start, mic not available on this device")
            return -4
        }
        
        if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PermissionChecker.PERMISSION_GRANTED) {
            AppLog.e("MicRecorder: No RECORD_AUDIO permission")
            return -3
        }

        val configuredSource = settings.micInputSource
        
        if (configuredSource == SOURCE_BLUETOOTH_SCO) {
            startScoAndRecord()
        } else {
            startRecording(configuredSource)
        }
        
        return 0
    }

    private fun startScoAndRecord() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // 1. Listen for SCO connection state
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                AppLog.d("MicRecorder: SCO State change: $state")
                
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    AppLog.i("MicRecorder: SCO Connected. Starting AudioRecord.")
                    // On many devices, even with SCO, we should use MIC or DEFAULT 
                    // as VOICE_COMMUNICATION might try to use the device's own noise cancellation.
                    startRecording(MediaRecorder.AudioSource.MIC)
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && bluetoothScoStarted) {
                    AppLog.w("MicRecorder: SCO Disconnected unexpectedly.")
                    stop()
                }
            }
        }
        
        ContextCompat.registerReceiver(context, scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)
        
        // 2. Start SCO
        AppLog.i("MicRecorder: Starting Bluetooth SCO...")
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
        bluetoothScoStarted = true
    }

    private fun startRecording(source: Int) {
        try {
            if (audioRecord != null) return // Already recording
            
            AppLog.i("MicRecorder: Initializing AudioRecord with source $source")
            audioRecord = AudioRecord(source, micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, micBufferSize)
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.e("MicRecorder: Failed to initialize AudioRecord")
                audioRecord = null
                return
            }
            
            audioRecord?.startRecording()
            
            threadMicAudioActive = true
            threadMicAudio = Thread({
                while (threadMicAudioActive) {
                    micAudioRead(micAudioBuf, micBufferSize)
                }
            }, "mic_audio").apply { start() }
            
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Error during startRecording", e)
            audioRecord = null
        }
    }
}
