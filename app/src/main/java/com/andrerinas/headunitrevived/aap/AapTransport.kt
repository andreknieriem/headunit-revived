package com.andrerinas.headunitrevived.aap

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
import com.andrerinas.headunitrevived.contract.ProjectionActivityRequest
import com.andrerinas.headunitrevived.decoder.AudioDecoder
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.ssl.SingleKeyKeyManager
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import javax.net.ssl.SSLEngineResult
import java.util.concurrent.atomic.AtomicInteger

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
 * @param externalSsl Optional singleton [AapSslContext] whose internal [javax.net.ssl.SSLContext]
 *   (and its `ClientSessionContext` session cache) survives across [AapTransport] recreations.
 *   When provided on the Java-SSL path, JSSE can resume the previous TLS session on reconnect,
 *   skipping 4–6 round-trips and saving 1–3 s of handshake time. Pass `null` to create a
 *   fresh [AapSslContext] per transport (no session resumption). Ignored when native SSL is
 *   active (`settings.useNativeSsl = true`).
 */
class AapTransport(
        audioDecoder: AudioDecoder,
        videoDecoder: VideoDecoder,
        audioManager: AudioManager,
        internal val settings: Settings,
        private val notification: BackgroundNotification,
        private val context: Context,
        private val externalSsl: AapSslContext? = null)
    : MicRecorder.Listener {

    val ssl: AapSsl = if (settings.useNativeSsl) {
        try {
            AppLog.i("Using Native SSL implementation")
            AapSslNative()
        } catch (e: Throwable) {
            AppLog.e("Failed to instantiate Native SSL, falling back to Java SSL", e)
            // Use the shared context when available so session resumption works on fallback.
            externalSsl ?: AapSslContext(SingleKeyKeyManager(context))
        }
    } else {
        AppLog.i("Using Java SSL implementation")
        // externalSsl is the singleton AapSslContext from AppComponent whose SSLContext
        // (and its ClientSessionContext session cache) survives across transport recreations,
        // enabling TLS session resumption on reconnect.
        externalSsl ?: AapSslContext(SingleKeyKeyManager(context))
    }

    internal val aapAudio: AapAudio
    internal val aapVideo: AapVideo
    private var sendThread: HandlerThread? = null
    private var pollThread: HandlerThread? = null
    private val micRecorder: MicRecorder = MicRecorder(settings.micSampleRate, context)
    private val sessionIds = SparseIntArray(4)
    private val startedSensors = HashSet<Int>(4)
    private val keyCodes = settings.keyCodes.entries.associateTo(mutableMapOf()) {
        it.value to it.key
    }
    private val modeManager: UiModeManager =
        context.getSystemService(UI_MODE_SERVICE) as UiModeManager
    private var connection: AccessoryConnection? = null
    private var aapRead: AapRead? = null
    var isQuittingAllowed: Boolean = false
    var ignoreNextStopRequest: Boolean = false
    /** Set by [AapControl] when VIDEO_FOCUS_NATIVE triggers a stop (user tapped Exit). */
    @Volatile var wasUserExit: Boolean = false
    @Volatile var onQuit: ((Boolean) -> Unit)? = null
    var onAudioFocusStateChanged: ((Boolean) -> Unit)? = null
    private var pollHandler: Handler? = null
    private val outstandingPings = AtomicInteger(0)
    private var heartbeatRunnable: Runnable? = null
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
    /** Tracks consecutive send/encrypt failures. Reset on any successful send. */
    private var consecutiveSendErrors = 0
    private val sendHandlerCallback = Handler.Callback {
        val ret = this.sendEncryptedMessage(
            data = it.obj as ByteArray,
            length = it.arg2
        )
        if (ret < 0) {
            consecutiveSendErrors++
            if (consecutiveSendErrors >= MAX_CONSECUTIVE_SEND_ERRORS) {
                AppLog.e("AapTransport: $consecutiveSendErrors consecutive send failures — quitting")
                quit(clean = false)
            }
        } else {
            consecutiveSendErrors = 0
        }
        return@Callback true
    }

    val isAlive: Boolean
        get() = pollThread?.isAlive ?: false

    init {
        micRecorder.listener = this
        aapAudio = AapAudio(audioDecoder, audioManager, settings)
        aapVideo = AapVideo(videoDecoder, settings)
    }

    internal fun startSensor(type: Int) {
        startedSensors.add(type)
    }

    private fun sendEncryptedMessage(data: ByteArray, length: Int): Int {
        val ba =
            ssl.encrypt(AapMessage.HEADER_SIZE, length - AapMessage.HEADER_SIZE, data)
        if (ba == null) {
            AppLog.w("SSL encrypt failed (channel=${data[0].toInt() and 0xFF})")
            return -1
        }

        ba.data[0] = data[0]
        ba.data[1] = data[1]
        Utils.intToBytes(ba.limit - AapMessage.HEADER_SIZE, 2, ba.data)

        val size = connection?.sendBlocking(ba.data, ba.limit, 2000) ?: -1

        if (size < 0) {
            AppLog.w("sendBlocking failed (channel=${data[0].toInt() and 0xFF}, len=${ba.limit})")
            return -1
        }

        if (AppLog.LOG_VERBOSE) {
            AppLog.v("Sent size: %d", size)
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
        micRecorder.listener = null
        pollThread?.quit()
        sendThread?.quit()
        
        try {
            // Don't join the poll thread from within itself — it would block for the full
            // timeout since the thread can't finish while it's waiting for itself to finish.
            if (Thread.currentThread() != pollThread) pollThread?.join(1000)
            sendThread?.join(1000)
        } catch (e: InterruptedException) {
            AppLog.e("Failed to join threads", e)
        }
        
        aapRead = null
        ssl.release()
        stopHeartbeat()
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
        sendHandler?.post { com.andrerinas.headunitrevived.utils.LegacyOptimizer.setHighPriority() }

        pollThread = HandlerThread("AapTransport:Handler::Poll", Process.THREAD_PRIORITY_AUDIO)
        pollThread!!.start()
        pollHandler = Handler(pollThread!!.looper, pollHandlerCallback)
        pollHandler?.post { com.andrerinas.headunitrevived.utils.LegacyOptimizer.setHighPriority() }

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
            notification,
            context
        )
        pollHandler?.sendEmptyMessage(MSG_POLL)
        startHeartbeat()
    }

    private fun handshake(connection: AccessoryConnection): Boolean {
        try {
            // Increased delay for AA 16.4+ stability
            SystemClock.sleep(500)
            val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)

            // Drain any stale data left in the USB pipe from a previous session
            // (e.g. old TLS records after a dongle reconnect). Short timeout so we
            // don't block if the pipe is already clean.
            var drained = 0
            while (true) {
                val n = connection.recvBlocking(buffer, buffer.size, 50, false)
                if (n <= 0) break
                drained += n
            }
            if (drained > 0) {
                AppLog.i("Handshake: Drained $drained bytes of stale data before version request")
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
        // Maximum wall-clock time allowed for the version-exchange phase of the AAP handshake.
        // Prevents the retry loop from blocking for minutes on an unresponsive USB device.
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        /** Interval between heartbeat pings (ms). */
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        /** Number of consecutive missed pong responses before triggering a disconnect. */
        private const val MAX_MISSED_PONGS = 3
        /** Number of consecutive send/encrypt failures before quitting the transport. */
        private const val MAX_CONSECUTIVE_SEND_ERRORS = 10
    }

    // -------------------------------------------------------------------------
    // Heartbeat
    // -------------------------------------------------------------------------

    /**
     * Starts a periodic heartbeat that sends a [Control.PingRequest] every
     * [HEARTBEAT_INTERVAL_MS]. Each ping increments [outstandingPings]; when a
     * [Control.PingResponse] arrives, [onPongReceived] resets the counter.
     * If [MAX_MISSED_PONGS] consecutive pings go unanswered, the transport
     * is quit with `clean = false`.
     */
    private fun startHeartbeat() {
        outstandingPings.set(0)
        heartbeatRunnable = object : Runnable {
            override fun run() {
                val missed = outstandingPings.incrementAndGet()
                if (missed > MAX_MISSED_PONGS) {
                    AppLog.e("Heartbeat: $missed pings unanswered — assuming connection dead")
                    quit(clean = false)
                    return
                }
                if (missed > 1) {
                    AppLog.w("Heartbeat: $missed consecutive pings without pong")
                }
                val ping = Control.PingRequest.newBuilder()
                    .setTimestamp(System.nanoTime())
                    .build()
                val msg = AapMessage(
                    Channel.ID_CTR,
                    Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE,
                    ping
                )
                send(msg)
                sendHandler?.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        sendHandler?.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { sendHandler?.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    /** Called by [AapControlService] when a PingResponse arrives. */
    fun onPongReceived() {
        outstandingPings.set(0)
    }
}
