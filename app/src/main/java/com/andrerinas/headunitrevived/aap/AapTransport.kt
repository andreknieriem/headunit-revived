package com.andrerinas.headunitrevived.aap

import android.app.UiModeManager
import android.content.Context
import android.content.Context.UI_MODE_SERVICE
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.KeyEvent
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.messages.KeyCodeEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.MediaAck
import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.aap.protocol.messages.NightModeEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.ScrollWheelEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.SensorEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.TouchEvent
import com.andrerinas.headunitrevived.aap.protocol.proto.Input
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.contract.ProjectionActivityRequest
import com.andrerinas.headunitrevived.decoder.AudioDecoder
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

class AapTransport(
        audioDecoder: AudioDecoder,
        videoDecoder: VideoDecoder,
        audioManager: AudioManager,
        private val settings: Settings,
        private val notification: BackgroundNotification,
        private val context: Context)
    : MicRecorder.Listener {

    val ssl: AapSsl = AapSslNative() //AapSslContext(SingleKeyKeyManager(context))

    private val aapAudio: AapAudio
    private val aapVideo: AapVideo
    private var sendThread: HandlerThread? = null
    private var pollThread: HandlerThread? = null
    private val micRecorder: MicRecorder = MicRecorder(settings.micSampleRate, context)
    private val sessionIds = SparseIntArray(4)
    private val startedSensors = HashSet<Int>(4)
    private val keyCodes = settings.keyCodes.entries.associateTo(mutableMapOf()) {
        it.value to it.key
    }
    private val modeManager: UiModeManager =  context.getSystemService(UI_MODE_SERVICE) as UiModeManager
    private var connection: AccessoryConnection? = null
    private var aapRead: AapRead? = null
    private var pollHandler: Handler? = null
    private val pollHandlerCallback = Handler.Callback {
        val ret = aapRead?.read() ?: -1
        if (pollHandler == null) {
            return@Callback false
        }
        pollHandler?.let {
            if (!it.hasMessages(MSG_POLL))
            {
                it.sendEmptyMessage(MSG_POLL)
            }
        }

        if (ret < 0) {
            this.quit()
        }
        return@Callback true
    }
    private var sendHandler: Handler? = null
    private val sendHandlerCallback = Handler.Callback {
        this.sendEncryptedMessage(
                data = it.obj as ByteArray,
                length = it.arg2
        )
        return@Callback true
    }

    val isAlive: Boolean
        get() = pollThread?.isAlive ?: false

    init {
        micRecorder.listener = this
        aapAudio = AapAudio(audioDecoder, audioManager)
        aapVideo = AapVideo(videoDecoder)
    }

    internal fun startSensor(type: Int) {
        startedSensors.add(type)
        if (type == Sensors.SensorType.NIGHT_VALUE) {
            send(NightModeEvent(false))
        }
    }

    private fun sendEncryptedMessage(data: ByteArray, length: Int): Int {
        val ba = ssl.encrypt(AapMessage.HEADER_SIZE, length - AapMessage.HEADER_SIZE, data) ?: return -1

        ba.data[0] = data[0]
        ba.data[1] = data[1]
        Utils.intToBytes(ba.limit - AapMessage.HEADER_SIZE, 2, ba.data)

        val size = connection!!.sendBlocking(ba.data, ba.limit, 250)

        if (AppLog.LOG_VERBOSE) {
            AppLog.v("Sent size: %d", size)
            AapDump.logvHex("US", 0, ba.data, ba.limit)
        }
        return 0
    }

    internal fun quit() {
        AppLog.i("AapTransport quitting")
        context.sendBroadcast(DisconnectIntent())
        micRecorder.listener = null
        pollThread?.quit()
        sendThread?.quit()
        aapRead = null
        pollHandler = null
        sendHandler = null
        pollThread = null
        sendThread = null
    }

    internal fun start(connection: AccessoryConnection): Boolean {
        AppLog.i("Start Aap transport for $connection")
        this.connection = connection

        sendThread = HandlerThread("AapTransport:Handler::Send", Process.THREAD_PRIORITY_AUDIO)
        sendThread!!.start()
        sendHandler = Handler(sendThread!!.looper, sendHandlerCallback)

        pollThread = HandlerThread("AapTransport:Handler::Poll", Process.THREAD_PRIORITY_AUDIO)
        pollThread!!.start()
        pollHandler = Handler(pollThread!!.looper, pollHandlerCallback)

        // Add a small delay before starting the handshake to give the phone time to initialize
        SystemClock.sleep(200) // 200ms delay

        if (!handshake(connection)) {
            quit()
            AppLog.e("Handshake failed")
            return false
        }

        aapRead = AapRead.Factory.create(connection, this, micRecorder, aapAudio, aapVideo, settings, notification, context)
        pollHandler!!.sendEmptyMessage(MSG_POLL)

        // Create and start Transport Thread
        return true
    }

    private fun handshake(connection: AccessoryConnection): Boolean {
        val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)

        AppLog.d("Handshake: Starting version request. TS: ${SystemClock.elapsedRealtime()}")
        // Version request
        val version = Messages.versionRequest
        var ret = connection.sendBlocking(version, version.size, 5000)
        AppLog.d("Handshake: Version request sent. ret: $ret. TS: ${SystemClock.elapsedRealtime()}")
        if (ret < 0) {
            AppLog.e("Handshake: Version request sendEncrypted ret: $ret")
            return false
        }

        AppLog.d("Handshake: Waiting for version response. TS: ${SystemClock.elapsedRealtime()}")
        ret = connection.recvBlocking(buffer, buffer.size, 5000, false)
        AppLog.d("Handshake: Version response received. ret: $ret. TS: ${SystemClock.elapsedRealtime()}")
        if (ret <= 0) {
            AppLog.e("Handshake: Version request recv ret: $ret")
            return false
        }
        AppLog.i("Handshake: Version response recv ret: %d", ret)

        AppLog.d("Handshake: Starting SSL prepare. TS: ${SystemClock.elapsedRealtime()}")
        // SSL
        ret = ssl.prepare()
        AppLog.d("Handshake: SSL prepare finished. ret: $ret. TS: ${SystemClock.elapsedRealtime()}")
        if (ret < 0) {
            AppLog.e("Handshake: SSL prepare failed: $ret")
            return false
        }

        var hs_ctr = 0
        while (hs_ctr++ < 2) {
            AppLog.d("Handshake: SSL loop %d. TS: ${SystemClock.elapsedRealtime()}", hs_ctr)
            val handshakeData = ssl.handshakeRead() ?: run {
                AppLog.e("Handshake: SSL handshakeRead failed. TS: ${SystemClock.elapsedRealtime()}")
                return false
            }
            AppLog.d("Handshake: SSL BIO read: %d. TS: ${SystemClock.elapsedRealtime()}", handshakeData.size)

            val bio = Messages.createRawMessage(Channel.ID_CTR, 3, 3, handshakeData)
            var size = connection.sendBlocking(bio, bio.size, 5000)
            AppLog.d("Handshake: SSL BIO sent: %d. TS: ${SystemClock.elapsedRealtime()}", size)

            size = connection.recvBlocking(buffer, buffer.size, 5000, false)
            AppLog.d("Handshake: SSL received: %d. TS: ${SystemClock.elapsedRealtime()}", size)
            if (size <= 0) {
                AppLog.e("Handshake: SSL receive error. TS: ${SystemClock.elapsedRealtime()}")
                return false
            }

            ret = ssl.handshakeWrite(6, size - 6, buffer)
            AppLog.d("Handshake: SSL BIO write: %d. TS: ${SystemClock.elapsedRealtime()}", ret)
        }

        AppLog.d("Handshake: SSL handshake complete. TS: ${SystemClock.elapsedRealtime()}")
        // Status = OK
        val status = Messages.statusOk
        ret = connection.sendBlocking(status, status.size, 5000)
        AppLog.d("Handshake: Status OK sent. ret: $ret. TS: ${SystemClock.elapsedRealtime()}")
        if (ret < 0) {
            AppLog.e("Handshake: Status request sendEncrypted ret: $ret")
            return false
        }

        AppLog.i("Handshake: Status OK sent: %d", ret)
        AppLog.d("Handshake: Handshake successful. TS: ${SystemClock.elapsedRealtime()}")
        return true
    }

    fun send(keyCode: Int, isPress: Boolean) {
        val mapped = keyCodes[keyCode] ?: keyCode
        val aapKeyCode = KeyCode.convert(mapped)

        if (mapped == KeyEvent.KEYCODE_GUIDE) {
            // Hack for navigation button to simulate touch
            val action = if (isPress)
                Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN else Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
            this.send(TouchEvent(SystemClock.elapsedRealtime(), action, 99, 444))
            return
        }

        if (mapped == KeyEvent.KEYCODE_N) {
            val enabled = modeManager.nightMode != UiModeManager.MODE_NIGHT_YES
            send(NightModeEvent(enabled))
            modeManager.nightMode = if (enabled) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            return
        }

        if (aapKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
            AppLog.i("Unknown: $keyCode")
        }

        val ts = SystemClock.elapsedRealtime()
        if (aapKeyCode == KeyEvent.KEYCODE_SOFT_LEFT|| aapKeyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            if (isPress) {
                val delta = if (aapKeyCode == KeyEvent.KEYCODE_SOFT_LEFT) -1 else 1
                send(ScrollWheelEvent(ts, delta))
            }
            return
        }

        send(KeyCodeEvent(ts, aapKeyCode, isPress))
    }

    fun send(sensor: SensorEvent): Boolean {
        return if (startedSensors.contains(sensor.sensorType)) {
            send(sensor as AapMessage)
            true
        } else {
            AppLog.e("Sensor " + sensor.sensorType + " is not started yet")
            false
        }
    }

    fun send(message: AapMessage) {
        if (sendHandler == null) {
            AppLog.e("Handler is null")
        } else {
            if (AppLog.LOG_VERBOSE) {
                AppLog.v(message.toString())
            }
            val msg = sendHandler!!.obtainMessage(MSG_SEND, 0, message.size, message.data)
            sendHandler!!.sendMessage(msg)
        }
    }

    internal fun gainVideoFocus() {
        context.sendBroadcast(ProjectionActivityRequest())
    }

    internal fun sendMediaAck(channel: Int) {
        send(MediaAck(channel, sessionIds.get(channel)))
    }

    internal fun setSessionId(channel: Int, sessionId: Int) {
        sessionIds.put(channel, sessionId)
    }

    override fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int) {
        if (mic_audio_len > 64) {  // If we read at least 64 bytes of audio data
            val length = mic_audio_len + 10
            val data = ByteArray(length)
            data[0] = Channel.ID_MIC.toByte()
            data[1] = 0x0b
            Utils.put_time(2, data, SystemClock.elapsedRealtime())
            System.arraycopy(mic_buf, 0, data, 10, mic_audio_len)
            send(AapMessage(Channel.ID_MIC, 0x0b.toByte(), -1, 2, length, data))
        }
    }

    companion object {
        private const val MSG_POLL = 1
        private const val MSG_SEND = 2
    }

}

