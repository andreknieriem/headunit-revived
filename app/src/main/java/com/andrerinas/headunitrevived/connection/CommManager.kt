package com.andrerinas.headunitrevived.connection
import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.andrerinas.headunitrevived.aap.AapSslContext
import com.andrerinas.headunitrevived.aap.AapTransport
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.ssl.SingleKeyKeyManager
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.andrerinas.headunitrevived.decoder.AudioDecoder
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.messages.SensorEvent
import com.andrerinas.headunitrevived.aap.protocol.proto.MediaPlayback
import java.net.Socket
import android.view.KeyEvent

/**
 * Central connection and transport lifecycle manager.
 *
 * CommManager owns the full lifecycle of both the physical connection ([AccessoryConnection])
 * and the AAP protocol layer ([AapTransport]). It exposes a single [connectionState] flow as
 * the source of truth; all consumers (AapService, AapProjectionActivity, UI fragments) observe
 * this flow reactively instead of being called imperatively.
 *
 * ## State machine
 * ```
 *   Disconnected ──connect()──► Connecting ──success──► Connected
 *                                                            │
 *                                                   startHandshake()
 *                                                            │
 *                                                   StartingTransport
 *                                                            │
 *                                                     SSL done
 *                                                            │
 *                                                   HandshakeComplete
 *                                                            │
 *                                                    startReading()
 *                                                            │
 *                                                    TransportStarted
 *                                                            │
 *                                      disconnect() / read error / phone bye-bye
 *                                                            │
 *                                                      Disconnected
 * ```
 *
 * ## Thread safety
 * [_transport] and [_connection] are `@Volatile`. All state mutations go through
 * [_connectionState] (a [MutableStateFlow]) which is thread-safe. The internal [_scope] uses
 * [Dispatchers.IO] with a [SupervisorJob] so individual coroutine failures do not cancel the
 * entire scope.
 */
class CommManager(
    private val context: Context,
    private val settings: Settings,
    private val audioDecoder: AudioDecoder,
    private val videoDecoder: VideoDecoder) {

    // Single AapSslContext for the lifetime of this CommManager. Its internal SSLContext holds
    // JSSE's ClientSessionContext session cache, which survives transport recreations and enables
    // abbreviated TLS handshakes on reconnect (session resumption).
    private val aapSslContext: AapSslContext = AapSslContext(SingleKeyKeyManager(context))

    /**
     * Represents the lifecycle state of the Android Auto connection.
     *
     * State transitions are strictly sequential — see the class-level diagram.
     */
    sealed class ConnectionState {
        /**
         * No active connection.
         * @param isClean `true` if the phone sent a graceful `ByeByeRequest` before closing;
         *                `false` for all other disconnect causes (USB detach, read error,
         *                socket timeout, explicit user disconnect).
         */
        data class Disconnected(
            val isClean: Boolean = false,
            val isUserExit: Boolean = false
        ) : ConnectionState()

        /** Physical connection handshake in progress (USB open or TCP connect). */
        object Connecting : ConnectionState()

        /** Physical connection established; AAP handshake not yet started. */
        object Connected : ConnectionState()

        /** AAP SSL handshake in progress. */
        object StartingTransport : ConnectionState()

        /**
         * SSL handshake complete;
         */
        object HandshakeComplete : ConnectionState()

        /** AAP handshake complete; the transport is ready to send and receive messages. */
        object TransportStarted : ConnectionState()

        /** A non-fatal error occurred. The manager transitions to [Disconnected] immediately after. */
        data class Error(val message: String) : ConnectionState()
    }

    /** IO-bound coroutine scope for all async connection work. SupervisorJob prevents one
     *  failing child from cancelling the rest. */
    private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lastKeyEvents = mutableMapOf<Int, Long>()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())

    /** Callback for audio focus state changes (isPlaying). Set by AapService. */
    var onAudioFocusStateChanged: ((Boolean) -> Unit)? = null

    /** Now-playing metadata from the phone (AAP media channel). Set by AapService. */
    var onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null
    /** Playback status from the phone (AAP media channel), includes current position. */
    var onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null

    /** @Volatile: written on IO thread, read on Main and IO threads. */
    @Volatile private var _transport: AapTransport? = null
    var onUpdateUiConfigReplyReceived: (() -> Unit)? = null
    @Volatile private var _connection: AccessoryConnection? = null

    /**
     * Tracks the most-recently-launched [doDisconnect] coroutine.
     * [connect] overloads join this job before opening a new connection, ensuring the previous
     * device is fully closed before `openDevice()` is called on it again.
     */
    @Volatile private var _disconnectJob: kotlinx.coroutines.Job? = null

    private val _backgroundNotification = BackgroundNotification(context)

    /** Public read-only view of [_connectionState]. */
    val connectionState = _connectionState.asStateFlow()

    /**
     * `true` while a physical connection exists, regardless of whether the AAP transport
     * handshake has completed. Covers [ConnectionState.Connected], [ConnectionState.StartingTransport],
     * and [ConnectionState.TransportStarted].
     */
    val isConnected: Boolean
        get() = connectionState.value.let {
            it is ConnectionState.Connected ||
            it is ConnectionState.StartingTransport ||
            it is ConnectionState.HandshakeComplete ||
            it is ConnectionState.TransportStarted
        }

    /**
     * Returns `true` if the current USB connection is to [device].
     * Used by AapService to decide whether a USB detach event should trigger a disconnect.
     */
    fun isConnectedToUsbDevice(device: UsbDevice): Boolean =
        (_connection as? UsbAccessoryConnection)?.isDeviceRunning(device) == true

    // -----------------------------------------------------------------------------------------
    // connect() overloads — one for each transport type
    // -----------------------------------------------------------------------------------------

    /**
     * Opens a USB accessory connection to [device].
     *
     * On success emits [ConnectionState.Connected] and persists the device as the last-used
     * connection so it can be auto-reconnected on the next launch.
     */
    suspend fun connect(device: UsbDevice) = withContext(Dispatchers.IO) {
        // Another caller already started the connection — do nothing.
        if (_connectionState.value is ConnectionState.Connecting)
            return@withContext


        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            _connectionState.emit(ConnectionState.Error("USB permission not granted for device"))
            return@withContext
        }

        // Wait for any in-progress cleanup to finish before opening the USB device.
        // Without this, openDevice() on the same hardware can return null because the previous
        // UsbDeviceConnection hasn't been close()d yet.
        _disconnectJob?.join()

        try {
            _connectionState.emit(ConnectionState.Connecting)
            _connection?.disconnect()
            _connection = UsbAccessoryConnection(usbManager, device)

            if (_connection?.connect() ?: false) {
                settings.saveLastConnection(type = Settings.CONNECTION_TYPE_USB, usbDevice = UsbDeviceCompat.getUniqueName(device))
                _connectionState.emit(ConnectionState.Connected)
            } else {
                _connectionState.emit(ConnectionState.Disconnected())
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Wraps an already-connected [Socket] (e.g. accepted by `WirelessServer`) in a
     * [SocketAccessoryConnection] and advances to [ConnectionState.Connected].
     *
     * The socket must already be connected; this overload skips the TCP handshake and only
     * sets up the AAP framing layer.
     */
    suspend fun connect(socket: Socket) = withContext(Dispatchers.IO) {
        // Another caller already started the connection — do nothing.
        if (_connectionState.value is ConnectionState.Connecting)
            return@withContext


        _disconnectJob?.join()

        try {
            _connectionState.emit(ConnectionState.Connecting)
            _connection?.disconnect()
            _connection = SocketAccessoryConnection(socket, context)

            if (_connection?.connect() ?: false) {
                // [FIX] Don't overwrite NEARBY connection type with WIFI + localhost IP (::1)
                if (socket !is NearbySocket) {
                    settings.saveLastConnection(type = Settings.CONNECTION_TYPE_WIFI, ip = socket.inetAddress?.hostAddress ?: "")
                }
                _connectionState.emit(ConnectionState.Connected)
            } else {
                _connectionState.emit(ConnectionState.Disconnected())
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Opens a TCP connection to [ip]:[port] and advances to [ConnectionState.Connected].
     *
     * Used by the manual IP entry flow and the NSD-discovered device list.
     */
    suspend fun connect(ip: String, port: Int) = withContext(Dispatchers.IO) {
        // Another caller already started the connection — do nothing.
        if (_connectionState.value is ConnectionState.Connecting)
            return@withContext

        _disconnectJob?.join()

        try {
            _connectionState.emit(ConnectionState.Connecting)
            _connection?.disconnect()
            _connection = SocketAccessoryConnection(ip, port, context)

            if (_connection?.connect() ?: false) {
                settings.saveLastConnection(type = Settings.CONNECTION_TYPE_WIFI, ip = ip)
                _connectionState.emit(ConnectionState.Connected)
            } else {
                _connectionState.emit(ConnectionState.Disconnected())
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Connection failed: ${e.message}"))
            disconnect()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Transport lifecycle
    // -----------------------------------------------------------------------------------------

    /**
     * Phase 1: runs the SSL handshake over the current connection.
     *
     * Must only be called when state is [ConnectionState.Connected]. On success, emits
     * [ConnectionState.HandshakeComplete] and returns; the inbound message loop is NOT
     * started yet. Call [startReading] after [VideoDecoder.setSurface] has been invoked
     * to begin receiving messages.
     *
     * The [AapTransport.onQuit] callback is wired here; it fires whenever the transport
     * stops (read error, phone bye-bye, timeout) and triggers [transportedQuited].
     *
     * Called by [com.andrerinas.headunitrevived.aap.AapService] in parallel with the
     * projection activity startup, so the handshake latency is hidden behind activity
     * inflation time rather than added on top of it.
     */
    suspend fun startHandshake() = withContext(Dispatchers.IO) {
        // Another caller already started the handshake — do nothing.
        if (_connectionState.value is ConnectionState.StartingTransport) return@withContext

        try {
            if (_connectionState.value is ConnectionState.Connected) {
                _connectionState.emit(ConnectionState.StartingTransport)

                if (_transport == null) {
                    val audioManager = context.getSystemService(Application.AUDIO_SERVICE) as AudioManager
                    _transport = AapTransport(
                        audioDecoder,
                        videoDecoder,
                        audioManager,
                        settings,
                        _backgroundNotification,
                        context,
                        externalSsl = aapSslContext,
                        onAaMediaMetadata = { meta -> onAaMediaMetadata?.invoke(meta) },
                        onAaPlaybackStatus = { status -> onAaPlaybackStatus?.invoke(status) }
                    )
                    _transport!!.onQuit = { isClean -> transportedQuited(isClean) }
                    _transport!!.onAudioFocusStateChanged = { isPlaying -> onAudioFocusStateChanged?.invoke(isPlaying) }
                    _transport!!.onUpdateUiConfigReplyReceived = { onUpdateUiConfigReplyReceived?.invoke() }
                }
                if (_transport?.startHandshake(_connection!!) == true) {
                    _connectionState.emit(ConnectionState.HandshakeComplete)
                } else {
                    _connectionState.emit(ConnectionState.Error("Handshake failed"))
                    disconnect()
                }
            } else {
                _connectionState.emit(ConnectionState.Error("Starting handshake without connection"))
            }
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Handshake failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Phase 2: starts the inbound message loop.
     *
     * Must only be called when state is [ConnectionState.HandshakeComplete], which implies
     * both that the SSL handshake has succeeded **and** that [VideoDecoder.setSurface] has
     * already been called by [com.andrerinas.headunitrevived.aap.AapProjectionActivity].
     * This ordering guarantees no video frame is ever decoded before a render target exists.
     *
     * On success:
     * 1. Claims audio focus for `STREAM_MUSIC`.
     * 2. Starts the [AapTransport] read loop.
     * 3. Emits [ConnectionState.TransportStarted].
     */
    suspend fun startReading() = withContext(Dispatchers.IO) {
        if (_connectionState.value !is ConnectionState.HandshakeComplete) return@withContext

        try {
            _transport?.aapAudio?.requestFocusChange(
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.OnAudioFocusChangeListener { }
            )
            _transport?.startReading()
            _connectionState.emit(ConnectionState.TransportStarted)
        } catch (e: Exception) {
            _connectionState.emit(ConnectionState.Error("Start reading failed: ${e.message}"))
            disconnect()
        }
    }

    /**
     * Called by `AapTransport.onQuit` when the transport stops itself (read error, socket
     * timeout, or phone-initiated graceful close).
     *
     * Sets state to [ConnectionState.Disconnected] synchronously (so [isConnected] returns
     * `false` immediately) then schedules cleanup. `sendByeBye` is `false` because the
     * connection is already dead — there is no point sending a `ByeByeRequest`.
     */
    private fun transportedQuited(isClean: Boolean) {
        val wasUserExit = _transport?.wasUserExit ?: false
        _connectionState.value = ConnectionState.Disconnected(isClean, isUserExit = wasUserExit)
        // Transport already quit on its own — no ByeByeRequest needed (connection is dead).
        _disconnectJob = _scope.launch { doDisconnect(sendByeBye = false) }
        if (settings.killOnDisconnect) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Stop the foreground service first to remove the notification
                val stopIntent = android.content.Intent(context, com.andrerinas.headunitrevived.aap.AapService::class.java).apply {
                    action = com.andrerinas.headunitrevived.aap.AapService.ACTION_STOP_SERVICE
                }
                com.andrerinas.headunitrevived.aap.AapService.killProcessOnDestroy = true
                context.stopService(stopIntent)
                // Finish all tasks (API 21+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    activityManager.appTasks.forEach { it.finishAndRemoveTask() }
                }
            }, 500)
        }
    }

    // -----------------------------------------------------------------------------------------
    // send() overloads — fire-and-forget; silently dropped if not TransportStarted
    // -----------------------------------------------------------------------------------------

    private val keyStates = mutableMapOf<Int, Boolean>()

    /** 
     * Sends a key press or release event to the phone with remapping and de-duplication.
     * This is the single entry point for all key events in the application.
     */
    fun sendKey(keyCode: Int, isPress: Boolean) {
        if (_connectionState.value !is ConnectionState.TransportStarted) {
            return
        }

        // 1. Remapping (Physical -> Logical)
        var logicalCode = settings.keyCodes.entries.find { it.value == keyCode }?.key ?: keyCode
        
        // [FIX] BMW/Rotary Enter remapping: Most AA apps expect DPAD_CENTER (23) for selection,
        // but physical rotary knobs often send ENTER (66). Remap 66 -> 23 to ensure selection works.
        if (logicalCode == KeyEvent.KEYCODE_ENTER) {
            logicalCode = KeyEvent.KEYCODE_DPAD_CENTER
        }

        // 2. State Tracking & De-duplication
        val isCurrentlyDown = keyStates[logicalCode] ?: false
        if (isPress == isCurrentlyDown) {
            return
        }

        // 3. Time-based Debouncing
        val now = SystemClock.elapsedRealtime()
        if (isPress) {
            val lastPressTime = lastKeyEvents[logicalCode] ?: 0L
            if (now - lastPressTime < 300) {
                AppLog.i("CommManager: Debouncing logical key $logicalCode (DOWN) - dropped duplicate trigger within ${now - lastPressTime}ms")
                return
            }
            lastKeyEvents[logicalCode] = now
        }

        // Update state
        keyStates[logicalCode] = isPress
        
        AppLog.i("CommManager: TX Key -> AA=$logicalCode (isPress=$isPress)")
        _transport?.send(logicalCode, isPress)
    }

    /** 
     * [Legacy] Internal transport send. Use sendKey() for physical button inputs.
     * @deprecated Use sendKey(keyCode, isPress) for unified remapping and debouncing.
     */
    fun send(keyCode: Int, isPress: Boolean) {
        if (_connectionState.value is ConnectionState.TransportStarted) {
            _transport?.send(keyCode, isPress)
        }
    }

    /** Sends a sensor event (e.g. driving status, night mode) to the phone. */
    fun send(sensor: SensorEvent) {
        if (_connectionState.value is ConnectionState.TransportStarted) {
            _transport?.send(sensor)
        }
    }

    /** Sends a raw [AapMessage] (e.g. touch event, video focus request) to the phone. */
    fun send(message: AapMessage) {
        if (_connectionState.value is ConnectionState.TransportStarted) {
            _transport?.send(message)
        }
    }

    fun sendUpdateUiConfigRequest(left: Int, top: Int, right: Int, bottom: Int) {
        val request = com.andrerinas.headunitrevived.aap.protocol.messages.UpdateUiConfigRequest(left, top, right, bottom)
        AppLog.i("[UI_DEBUG_FIX] TX UpdateUiConfigRequest: L=$left T=$top R=$right B=$bottom")
        send(request)
        // HUR always sends VideoFocusNotification(PROJECTED, unsolicited=true) after
        // updating the UI config. This triggers a keyframe from the phone.
        send(com.andrerinas.headunitrevived.aap.protocol.messages.VideoFocusEvent(gain = true, unsolicited = true))
    }

    // -----------------------------------------------------------------------------------------
    // Disconnect
    // -----------------------------------------------------------------------------------------

    /**
     * Initiates a user-requested disconnect.
     *
     * Sets state to [ConnectionState.Disconnected] synchronously so callers see the change
     * immediately, then schedules async cleanup via [doDisconnect]. A ByeByeRequest is sent
     * to the phone before closing the connection.
     */
    fun disconnect(sendByeBye: Boolean = true) {
        if (_connectionState.value is ConnectionState.Disconnected) return

        HeadUnitScreenConfig.unlockResolution()

        _connectionState.value = ConnectionState.Disconnected(isUserExit = true)
        _transport?.wasUserExit = true
        _disconnectJob = _scope.launch { doDisconnect(sendByeBye) }
        if (settings.killOnDisconnect) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Stop the foreground service first to remove the notification
                val stopIntent = android.content.Intent(context, com.andrerinas.headunitrevived.aap.AapService::class.java).apply {
                    action = com.andrerinas.headunitrevived.aap.AapService.ACTION_STOP_SERVICE
                }
                com.andrerinas.headunitrevived.aap.AapService.killProcessOnDestroy = true
                context.stopService(stopIntent)
                // Finish all tasks (API 21+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    activityManager.appTasks.forEach { it.finishAndRemoveTask() }
                }
            }, 500)
        }
    }

    /**
     * Tears down the transport and physical connection.
     *
     * **Null-first pattern**: [_transport] and [_connection] are captured and nulled at the
     * very start. This prevents re-entrant double-cleanup: `AapTransport.stop()` fires `onQuit`
     * → [transportedQuited] → a second [doDisconnect] call — which now finds both fields null
     * and exits cleanly.
     *
     * @param sendByeBye `true` (default) when the user initiates the disconnect — calls
     *   `AapTransport.stop()`, which sends a `ByeByeRequest` to the phone and waits ~150 ms
     *   for acknowledgement. `false` when the transport self-quit (read error, socket timeout):
     *   the connection is already dead, so `AapTransport.quit()` is called directly to skip
     *   the send and the sleep.
     */
    private fun doDisconnect(sendByeBye: Boolean = true) {
        // Capture and null out immediately to prevent a second doDisconnect() call
        // (from transportedQuited firing onQuit during stop()) from double-stopping.
        val transport = _transport
        val connection = _connection
        _transport = null
        _connection = null
        lastKeyEvents.clear()
        keyStates.clear()
        try {
            // Only send ByeByeRequest when we are initiating the disconnect (e.g. user pressed
            // disconnect). When the transport self-quit (read error, soTimeout), the connection
            // is already dead — skip the send and the 150 ms sleep inside stop().
            if (sendByeBye) transport?.stop() else transport?.quit()
            
            // Explicitly stop and release decoders to prevent MediaCodec finalize() timeouts
            videoDecoder.stop("CommManager: doDisconnect")
            audioDecoder.stop()
            
            connection?.disconnect()
        } catch (e: Exception) {
            AppLog.e("doDisconnect error: ${e.message}")
        } finally {
            if (_connectionState.value !is ConnectionState.Disconnected) {
                _connectionState.value = ConnectionState.Disconnected()
            }
        }
    }

    /**
     * Performs a final disconnect and cancels the internal coroutine scope.
     *
     * Call this when the owning component (e.g. the foreground service) is destroyed.
     * After [destroy], the CommManager instance must not be used again.
     */
    fun destroy() {
        doDisconnect()
        _scope.cancel()
    }
}
