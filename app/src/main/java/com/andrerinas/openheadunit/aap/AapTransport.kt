package com.andrerinas.openheadunit.aap

import android.app.UiModeManager
import android.content.Context
import android.content.Context.UI_MODE_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.SparseIntArray
import android.view.KeyEvent
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.messages.KeyCodeEvent
import com.andrerinas.openheadunit.aap.protocol.messages.MediaAck
import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import com.andrerinas.openheadunit.aap.protocol.messages.ScrollWheelEvent
import com.andrerinas.openheadunit.aap.protocol.messages.SensorEvent
import com.andrerinas.openheadunit.utils.LegacyOptimizer
import com.andrerinas.openheadunit.connection.AccessoryConnection
import com.andrerinas.openheadunit.connection.SocketAccessoryConnection
import com.andrerinas.openheadunit.contract.ProjectionActivityRequest
import com.andrerinas.openheadunit.decoder.AudioDecoder
import com.andrerinas.openheadunit.decoder.MicRecorder
import com.andrerinas.openheadunit.decoder.VideoDecoder
import com.andrerinas.openheadunit.main.BackgroundNotification
import com.andrerinas.openheadunit.ssl.SingleKeyKeyManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import javax.net.ssl.SSLEngineResult

/**
 * Core AAP message pump.
 *
 * Owns two [HandlerThread]s:
 * - **Send** (`AapTransport:Handler::Send`) — encrypts and delivers outbound messages.
 * - **Poll** (`AapTransport:Handler::Poll`) — reads, decrypts, and dispatches inbound messages.
 *
 * Lifecycle: [startHandshake] → [startReading] → message loop → [stop]/[quit].
 *
 * @param audioDecoder Decodes PCM audio received from the phone.
 * @param videoDecoder Decodes H.264/H.265 video received from the phone.
 * @param audioManager Used to request and release audio focus.
 * @param settings User preferences (SSL mode, key mappings, microphone sample rate, …).
 * @param notification Background notification handle; updated as connection state changes.
 * @param context Application context; used for broadcasts and system services.
 * @param onAaMediaMetadata Optional callback when the phone sends media metadata (now-playing).
 * @param onAaPlaybackStatus Optional callback when the phone sends playback status/position.
 * @param externalSsl Optional singleton [AapSslContext] whose internal [javax.net.ssl.SSLContext]
 *   (and its `ClientSessionContext` session cache) survives across [AapTransport] recreations.
 *   When provided, JSSE can resume the previous TLS session on reconnect, skipping 4–6
 *   round-trips and saving 1–3 s of handshake time. Pass `null` to create a fresh
 *   [AapSslContext] per transport (no session resumption).
 */
class AapTransport(
        audioDecoder: AudioDecoder,
        private val videoDecoder: VideoDecoder,
        audioManager: AudioManager,
        internal val settings: Settings,
        private val notification: BackgroundNotification,
        private val context: Context,
        private val onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null,
        private val onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null,
        private val externalSsl: AapSslContext? = null)
    : MicRecorder.Listener {

    val ssl: AapSsl = externalSsl ?: AapSslContext(SingleKeyKeyManager(context))

    internal val aapAudio: AapAudio
    internal val aapVideo: AapVideo
    private var sendThread: HandlerThread? = null
    private var pollThread: HandlerThread? = null
    private val micRecorder: MicRecorder = MicRecorder(settings.micSampleRate, context)
    private val sessionIds = SparseIntArray(4)
    private val startedSensors = HashSet<Int>(4)
    private val keyCodes = mutableMapOf<Int, Int>()
    private val modeManager: UiModeManager =
        context.getSystemService(UI_MODE_SERVICE) as UiModeManager
    private var connection: AccessoryConnection? = null
    private var aapRead: AapRead? = null
    var isQuittingAllowed: Boolean = false

    val isWireless: Boolean
        get() = connection is com.andrerinas.openheadunit.connection.SocketAccessoryConnection
    var ignoreNextStopRequest: Boolean = false
    /** Set by [AapControl] when VIDEO_FOCUS_NATIVE triggers a stop (user tapped Exit). */
    @Volatile var wasUserExit: Boolean = false
    @Volatile var onQuit: ((Boolean) -> Unit)? = null
    var isAssistantActive = false
    var onAudioFocusStateChanged: ((Boolean) -> Unit)? = null
    var onUpdateUiConfigReplyReceived: (() -> Unit)? = null
    private var pollHandler: Handler? = null
    private val pollHandlerCallback = Handler.Callback {
        val readInstance = aapRead
        if (readInstance == null) {
            return@Callback false
        }

        val ret = readInstance.read()

        if (ret < 0) {
            AppLog.i("Quitting because ret < 0 ($ret)")
            this.quit(clean = (ret == -2))
            return@Callback true
        }

        pollHandler?.let {
            if (!it.hasMessages(MSG_POLL)) {
                it.sendEmptyMessage(MSG_POLL)
            }
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

    private fun triggerFocusCycleRecovery() {
        // Ask the phone for a fresh keyframe with an unsolicited focus GAIN only, the same nudge the
        // startup keyframe watchdog uses. We deliberately do not release focus (gain=false) first:
        // on some head unit / phone combos, releasing video focus drops the stream to a few fps and
        // it never recovers, so a single transient dropped frame turned into a permanent freeze
        // (issue #755). A gain-only request still makes the phone resend a keyframe without
        // disturbing the running stream.
        AppLog.w("AapTransport: Requesting recovery keyframe (unsolicited focus gain).")
        send(com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent(gain = true, unsolicited = true))
    }

    init {
        micRecorder.listener = this
        aapAudio = AapAudio(audioDecoder, audioManager, settings, context)
        aapVideo = AapVideo(videoDecoder, settings) {
            triggerFocusCycleRecovery()
        }

        videoDecoder.onDecoderError = {
            triggerFocusCycleRecovery()
        }
    }

    internal fun startSensor(type: Int) {
        startedSensors.add(type)
    }

    private fun sendEncryptedMessage(data: ByteArray, length: Int): Int {
        val ba =
            ssl.encrypt(AapMessage.HEADER_SIZE, length - AapMessage.HEADER_SIZE, data) ?: return -1

        ba.data[0] = data[0]
        ba.data[1] = data[1]
        Utils.intToBytes(ba.limit - AapMessage.HEADER_SIZE, 2, ba.data)

        val size = connection?.sendBlocking(ba.data, ba.limit, 250) ?: -1

        if (AppLog.LOG_VERBOSE) {
            AppLog.v("Sent size: %d", size)
            // AapDump.logvHex("US", 0, ba.data, ba.limit) // AapDump might be removed or changed
        }
        return 0
    }

    internal fun stop() {
        AppLog.i("AapTransport stopping and sending byebye")
        val byebye = Control.ByeByeRequest.newBuilder()
            .setReason(Control.ByeByeReason.USER_SELECTION)
            .build()
        val msg =
            AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE, byebye)
        send(msg)
        SystemClock.sleep(150)
        quit()
    }

    internal fun quit(clean: Boolean = false) {
        val cb = onQuit ?: return
        onQuit = null

        AppLog.i("AapTransport quitting (clean=$clean)")
        cb.invoke(clean)
        micRecorder.stop()
        micRecorder.listener = null
        pollThread?.quit()
        sendThread?.quit()
        aapAudio.releaseAllFocus()
        aapVideo.release()

        videoDecoder.onDecoderError = null

        try {            // Don't join the poll thread from within itself — it would block for the full
            // timeout since the thread can't finish while it's waiting for itself to finish.
            if (Thread.currentThread() != pollThread) pollThread?.join(1000)
            sendThread?.join(1000)
        } catch (e: InterruptedException) {
            AppLog.e("Failed to join threads", e)
        }

        aapRead = null
        ssl.release()
        pollHandler = null
        sendHandler = null
        pollThread = null
        sendThread = null
    }

    /**
     * Phase 1 of startup: creates the send/poll threads and runs the SSL handshake.
     *
     * Returns `true` on success. On failure, threads are stopped via [quit] before returning.
     * Must be followed by [startReading] (called after the projection surface is ready)
     * to actually start the message loop.
     */
    internal fun startHandshake(connection: AccessoryConnection): Boolean {
        AppLog.i("Start Aap transport handshake for $connection")
        this.connection = connection
        wasUserExit = false

        sendThread = HandlerThread("AapTransport:Handler::Send", Process.THREAD_PRIORITY_AUDIO)
        sendThread!!.start()
        sendHandler = Handler(sendThread!!.looper, sendHandlerCallback)
        sendHandler?.post { LegacyOptimizer.setHighPriority() }

        pollThread = HandlerThread("AapTransport:Handler::Poll", Process.THREAD_PRIORITY_AUDIO)
        pollThread!!.start()
        pollHandler = Handler(pollThread!!.looper, pollHandlerCallback)
        pollHandler?.post { LegacyOptimizer.setHighPriority() }

        // No sleep needed here: Handler(thread.looper, ...) already blocks internally until the
        // HandlerThread's Looper is ready (via HandlerThread.getLooper() → wait/notifyAll).

        if (!handshake(connection)) {
            quit()
            AppLog.e("Handshake failed")
            return false
        }

        return true
    }

    /**
     * Phase 2 of startup: creates [AapRead] and posts the first [MSG_POLL] to begin the
     * inbound message loop.
     *
     * Must only be called after [startHandshake] has returned `true` **and** after the
     * projection surface has been set on the [VideoDecoder]. This guarantees that no video
     * frame is ever decoded before a render target exists.
     */
    internal fun startReading() {
        AppLog.i("Start Aap transport read loop")
        aapRead = AapRead.Factory.create(
            connection!!,
            this,
            micRecorder,
            aapAudio,
            aapVideo,
            settings,
            context,
            onAaMediaMetadata,
            onAaPlaybackStatus
        )
        pollHandler?.sendEmptyMessage(MSG_POLL)
    }

    private fun handshake(connection: AccessoryConnection): Boolean {
        try {
            val isUsb = connection !is SocketAccessoryConnection && !connection.isSingleMessage
            // Increased delay for AA 16.4+ stability on USB - skip for Sockets
            if (isUsb) {
                SystemClock.sleep(500)
            }

            val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)

            // Drain any stale data left in the USB pipe from a previous session.
            // MUST ONLY be run on USB; on TCP Sockets, draining discards initial response bytes!
            var drained = 0
            if (isUsb) {
                while (true) {
                    val n = try { connection.recvBlocking(buffer, buffer.size, 50, false) } catch (e: Exception) { -1 }
                    if (n <= 0) break
                    drained += n
                }
                if (drained > 0) {
                    AppLog.i("Handshake: Drained $drained bytes of stale USB data before version request")
                }
            }

            AppLog.d("Handshake: Starting version request. TS: ${SystemClock.elapsedRealtime()}")
            val version = Messages.versionRequest
            var ret = -1
            var attempt = 0
            var received = false
            // Outer deadline prevents the loop from running for minutes on an unresponsive device.
            // Each send+recv pair uses 2 s per operation; 3 attempts × 4 s ≈ 12 s worst-case,
            // capped here at HANDSHAKE_TIMEOUT_MS so a stuck device fails fast.
            val versionDeadline = SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
            while (attempt < 3 && connection.isConnected) {
                if (SystemClock.elapsedRealtime() >= versionDeadline) {
                    AppLog.e("Handshake: Version exchange timed out after $attempt attempt(s).")
                    return false
                }
                attempt++
                ret = connection.sendBlocking(version, version.size, 2000)
                AppLog.d("Handshake: Version request sent. ret: $ret. attempt: $attempt. TS: ${SystemClock.elapsedRealtime()}")
                if (ret < 0) {
                    AppLog.w("Handshake: Version request send failed (ret=$ret), attempt $attempt")
                    SystemClock.sleep(200)
                    continue
                }

                AppLog.d("Handshake: Waiting for version response. TS: ${SystemClock.elapsedRealtime()}")
                // Inner loop: drain messages until we see channel=0 type=2 (VERSION_RESPONSE).
                // On first connection the phone may send a proactive message (e.g. a ping or a
                // status) before the version response arrives. Accepting any non-empty read as
                // "version response received" would hand a random payload to the SSL layer and
                // cause a 15 s timeout. Instead, discard unexpected messages and keep reading
                // until the deadline expires.
                val recvDeadline = SystemClock.elapsedRealtime() + 2000
                while (SystemClock.elapsedRealtime() < recvDeadline) {
                    val remaining = (recvDeadline - SystemClock.elapsedRealtime())
                        .toInt().coerceAtLeast(100)
                    ret = connection.recvBlocking(buffer, buffer.size, remaining, false)
                    if (ret <= 0) break  // timeout or error — fall through to outer retry
                    if (ret >= 6
                        && buffer[0] == 0.toByte()
                        && buffer[4] == 0.toByte()
                        && buffer[5] == 2.toByte()) {
                        AppLog.i("Handshake: Version response received (ret=$ret, attempt=$attempt).")
                        received = true
                        break
                    }
                    // Wrong message — log and keep draining.
                    val ch   = buffer[0].toInt() and 0xFF
                    val type = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
                    AppLog.w("Handshake: Ignoring unexpected message " +
                             "(ch=$ch, type=0x${type.toString(16)}, len=$ret). " +
                             "Waiting for VERSION_RESPONSE.")
                }
                if (received) break
                AppLog.w("Handshake: No VERSION_RESPONSE within 2s (attempt $attempt), ret=$ret")
                SystemClock.sleep(200)
            }

            if (!received) {
                AppLog.e("Handshake: Version request/response failed after $attempt attempt(s). last ret: $ret")
                return false
            }
            AppLog.i("Handshake: Version response recv ret: %d", ret)

            AppLog.d("Handshake: Starting SSL handshake via performHandshake(). TS: ${SystemClock.elapsedRealtime()}")
            if (!ssl.performHandshake(connection)) {
                AppLog.e("Handshake: SSL performHandshake failed.")
                return false
            }

            ssl.postHandshakeReset()
            AppLog.d("Handshake: SSL buffers reset after handshake.")

            AppLog.d("Handshake: SSL handshake complete. TS: ${SystemClock.elapsedRealtime()}")
            // Status = OK
            val status = Messages.statusOk
            ret = connection.sendBlocking(status, status.size, 2000)
            AppLog.d("Handshake: Status OK sent. ret: $ret. TS: ${SystemClock.elapsedRealtime()}")
            if (ret < 0) {
                AppLog.e("Handshake: Status request sendEncrypted ret: $ret")
                return false
            }

            AppLog.i("Handshake: Status OK sent: %d", ret)
            AppLog.d("Handshake: Handshake successful. TS: ${SystemClock.elapsedRealtime()}")

            return true
        } catch (e: Exception) {
            AppLog.e("Handshake failed with exception", e)
            return false
        }
    }

    fun send(keyCode: Int, isPress: Boolean) {
        val aapKeyCode = KeyCode.convert(keyCode)

        if (keyCode == KeyEvent.KEYCODE_N) {
            val intent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
            return
        }

        if (aapKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
            AppLog.i("Unknown: $keyCode")
        }

        val ts = SystemClock.elapsedRealtime()
        if (aapKeyCode == KeyEvent.KEYCODE_SOFT_LEFT || aapKeyCode == KeyEvent.KEYCODE_SOFT_RIGHT) {
            if (isPress) {
                val delta = if (aapKeyCode == KeyEvent.KEYCODE_SOFT_LEFT) -1 else 1
                send(ScrollWheelEvent(ts, delta))
            }
            return
        }

        send(KeyCodeEvent(ts, aapKeyCode, isPress))
    }

    fun send(sensor: SensorEvent): Boolean {
        return if (isAlive && startedSensors.contains(sensor.sensorType)) {
            send(sensor as AapMessage)
            true
        } else {
            if (!isAlive) {
                //AppLog.w("AapTransport not alive, ignoring sensor event for sensor ${sensor.sensorType}")
            } else {
                //AppLog.e("Sensor " + sensor.sensorType + " is not started yet")
            }
            false
        }
    }

    fun send(message: AapMessage) {
        val handler = sendHandler
        if (handler == null) {
            AppLog.i("Cannot send message, handler is null (quitting?)")
        } else {
            if (AppLog.LOG_VERBOSE) {
                AppLog.v(message.toString())
            }
            val msg = handler.obtainMessage(MSG_SEND, 0, message.size, message.data)
            handler.sendMessage(msg)
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
            val length = mic_audio_len + 12  // 4 header + 8 timestamp + PCM
            val data = ByteArray(length)
            data[0] = Channel.ID_MIC.toByte()
            data[1] = 0x0b
            // Timestamp at byte 4 so the full 8 bytes are inside the encrypted payload (HEADER_SIZE=4)
            Utils.put_time(4, data, SystemClock.elapsedRealtime())
            System.arraycopy(mic_buf, 0, data, 12, mic_audio_len)
            send(AapMessage(Channel.ID_MIC, 0x0b.toByte(), -1, 2, length, data))
        }
    }

    companion object {
        private const val MSG_POLL = 1
        private const val MSG_SEND = 2
        // Maximum wall-clock time allowed for the version-exchange phase of the AAP handshake.
        // Prevents the retry loop from blocking for minutes on an unresponsive USB device.
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
    }
}
