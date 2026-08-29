package com.andrerinas.openheadunit.decoder.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

class MicRecorder(private val micSampleRate: Int, private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
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

        /**
         * True while the uplink is the one holding MODE_IN_COMMUNICATION for SCO routing.
         *
         * Read by anything that infers a phone call from the audio mode, so our own microphone
         * does not look like one.
         */
        @Volatile
        var holdsCommunicationMode = false
            private set
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
        
        try {
            aec?.release()
            ns?.release()
            agc?.release()
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Error releasing AudioFX", e)
        }
        aec = null
        ns = null
        agc = null

        if (bluetoothScoStarted) {
            cleanupSco()
        }
        holdsCommunicationMode = false
    }

    private fun cleanupSco() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            scoReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {}
        scoReceiver = null
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.communicationDevice
            if (device != null) {
                AppLog.i("MicRecorder: Clearing communication device: ${device.productName}")
            }
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
        }

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Failed to restore audio mode to MODE_NORMAL", e)
        }

        bluetoothScoStarted = false
        AppLog.i("MicRecorder: Bluetooth SCO stopped and audio settings restored")
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

        currentListener.onMicDataAvailable(aud_buf, len)
        return len
    }

    private fun getAudioSource(index: Int): Int {
        return when (index) {
            0 -> MediaRecorder.AudioSource.DEFAULT
            1 -> MediaRecorder.AudioSource.MIC
            2 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            3 -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            4, SOURCE_BLUETOOTH_SCO -> SOURCE_BLUETOOTH_SCO
            else -> MediaRecorder.AudioSource.DEFAULT
        }
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

        val configuredSource = getAudioSource(settings.micInputSource)
        
        if (configuredSource == SOURCE_BLUETOOTH_SCO) {
            startScoAndRecord()
        } else {
            startRecording(configuredSource)
        }
        
        return 0
    }

    private fun startScoAndRecord() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Check for BLUETOOTH_CONNECT permission on Android 12+ (API 31+)
        val hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionChecker.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PermissionChecker.PERMISSION_GRANTED
        } else {
            true
        }

        // Set audio mode to MODE_IN_COMMUNICATION to force SCO routing
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            holdsCommunicationMode = true
        } catch (e: Exception) {
            AppLog.e("MicRecorder: Failed to set audio mode to MODE_IN_COMMUNICATION", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBluetoothPermission) {
            AppLog.i("MicRecorder: API 31+. Using setCommunicationDevice for Bluetooth routing.")
            val devices = audioManager.availableCommunicationDevices
            val bluetoothDevice = devices.find { 
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (bluetoothDevice != null) {
                val success = audioManager.setCommunicationDevice(bluetoothDevice)
                AppLog.i("MicRecorder: setCommunicationDevice result: $success for device: ${bluetoothDevice.productName} (${bluetoothDevice.type})")
            } else {
                AppLog.w("MicRecorder: No Bluetooth SCO/BLE headset found in available communication devices.")
            }
            // On API 31+, we can start recording directly on the communication channel
            startRecording(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            bluetoothScoStarted = true
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                AppLog.w("MicRecorder: Missing BLUETOOTH_CONNECT permission on API 31+. Falling back to legacy SCO.")
            }
            // Legacy path (API < 31 or missing BLUETOOTH_CONNECT permission on API 31+)
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
    }

    private fun startRecording(source: Int) {
        try {
            if (audioRecord != null) return // Already recording
            
            AppLog.i("MicRecorder: Initializing AudioRecord with source: ${getAudioSourceName(source)} ($source), SampleRate: $micSampleRate, BufferSize: $micBufferSize")
            audioRecord = AudioRecord(source, micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, micBufferSize)
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLog.e("MicRecorder: Failed to initialize AudioRecord")
                audioRecord = null
                return
            }
            
            val audioSessionId = audioRecord?.audioSessionId ?: 0
            if (audioSessionId != 0) {
                try {
                    if (settings.micNoiseSuppressor && NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(audioSessionId)
                        ns?.enabled = true
                        AppLog.i("MicRecorder: NoiseSuppressor: ${if (ns?.enabled == true) "ON" else "failed"}")
                    } else if (settings.micNoiseSuppressor) {
                        AppLog.i("MicRecorder: NoiseSuppressor: Unsupported on this device")
                    }
                    
                    if (settings.micAutoGainControl && AutomaticGainControl.isAvailable()) {
                        agc = AutomaticGainControl.create(audioSessionId)
                        agc?.enabled = true
                        AppLog.i("MicRecorder: AutomaticGainControl: ${if (agc?.enabled == true) "ON" else "failed"}")
                    } else if (settings.micAutoGainControl) {
                        AppLog.i("MicRecorder: AutomaticGainControl: Unsupported on this device")
                    }
                    
                    if (settings.micEchoCanceler && AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(audioSessionId)
                        aec?.enabled = true
                        AppLog.i("MicRecorder: AcousticEchoCanceler: ${if (aec?.enabled == true) "ON" else "failed"}")
                    } else if (settings.micEchoCanceler) {
                        AppLog.i("MicRecorder: AcousticEchoCanceler: Unsupported on this device")
                    }
                } catch (e: Exception) {
                    AppLog.e("MicRecorder: Error initializing AudioFX", e)
                }
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

    private fun getAudioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            SOURCE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            else -> "UNKNOWN ($source)"
        }
    }
}
