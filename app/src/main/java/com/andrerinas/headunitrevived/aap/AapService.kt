package com.andrerinas.headunitrevived.aap

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.Parcel
import android.os.Parcelable
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.NightModeEvent
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.connection.NetworkDiscovery
import com.andrerinas.headunitrevived.connection.WifiDirectManager
import android.support.v4.media.session.MediaSessionCompat
import com.andrerinas.headunitrevived.connection.SocketAccessoryConnection
import com.andrerinas.headunitrevived.connection.UsbAccessoryConnection
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.contract.ConnectedIntent
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.location.GpsLocationService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.DeviceIntent
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.utils.NightModeManager
import com.andrerinas.headunitrevived.utils.Settings
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class AapService : Service(), UsbReceiver.Listener {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var uiModeManager: UiModeManager
    private var accessoryConnection: AccessoryConnection? = null
    private lateinit var usbReceiver: UsbReceiver
    private var nightModeManager: NightModeManager? = null
    private var wifiDirectManager: WifiDirectManager? = null
    private var wirelessServer: WirelessServer? = null
    private var mediaSession: MediaSessionCompat? = null

    fun updateMediaSessionState(isPlaying: Boolean) {
        val state = if (isPlaying) {
            android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
        } else {
            android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
        }
        
        mediaSession?.setPlaybackState(
            android.support.v4.media.session.PlaybackStateCompat.Builder()
                .setState(state, 0, 1.0f)
                .setActions(android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or 
                           android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or 
                           android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP)
                .build()
        )
        AppLog.d("MediaSession: State updated to ${if (isPlaying) "PLAYING" else "STOPPED"}")
    }

    private var pendingConnectionType: String = ""
    private var pendingConnectionIp: String = ""
    private var pendingConnectionUsbDevice: String = ""
    private val connectionAttemptId = AtomicInteger(0)
    private var isDestroying = false

    private var usbStabilityJob: Job? = null
    private var stableDeviceName: String? = null

    private val transport: AapTransport
        get() = App.provide(this).transport

    private val nightModeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REQUEST_NIGHT_MODE_UPDATE) {
                AppLog.i("Received request to resend night mode state")
                nightModeManager?.resendCurrentState()
            }
        }
    }

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DisconnectIntent.action) {
                val isClean = intent.getBooleanExtra(DisconnectIntent.EXTRA_CLEAN, false)
                if (isConnected) {
                    AppLog.i("AapService received disconnect intent (clean=$isClean) -> closing connection")
                    onDisconnect(isClean)
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...");

        startForeground(1, createNotification());

        uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager;
        uiModeManager.enableCarMode(0);

        usbReceiver = UsbReceiver(this);
        wifiDirectManager = WifiDirectManager(this);
        
        nightModeManager = NightModeManager(this, App.provide(this).settings) { isNight ->
            AppLog.i("NightMode update: $isNight")
            App.provide(this).transport.send(NightModeEvent(isNight))
        }
        nightModeManager?.start()

        val nightModeFilter = IntentFilter(ACTION_REQUEST_NIGHT_MODE_UPDATE)
        val disconnectFilter = IntentFilter(DisconnectIntent.action)
        val usbFilter = UsbReceiver.createFilter()

        ContextCompat.registerReceiver(this, nightModeUpdateReceiver, nightModeFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, disconnectReceiver, disconnectFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, usbReceiver, usbFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        startService(GpsLocationService.intent(this));

        val mode = App.provide(this).settings.wifiConnectionMode
        if (mode == 2) {
            startWirelessServer();
            wifiDirectManager?.makeVisible();
        }

        checkAlreadyConnectedUsb();
    }

    private fun checkAlreadyConnectedUsb() {
        val settings = App.provide(this).settings
        val lastSession = settings.autoConnectLastSession
        val singleUsb = settings.autoConnectSingleUsbDevice

        if (!lastSession && !singleUsb) {
            AppLog.i("checkAlreadyConnectedUsb: skipped (no auto-connect modes enabled)")
            return
        }
        if (isConnected) {
            AppLog.i("checkAlreadyConnectedUsb: skipped (already connected)")
            return
        }
        if (isConnecting.get()) {
            AppLog.i("checkAlreadyConnectedUsb: skipped (connection in progress)")
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            val deviceCompat = UsbDeviceCompat(device)
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                AppLog.i("Found device already in accessory mode: ${deviceCompat.uniqueName}")
                handleConnectionIntent(createIntent(device, this))
                return
            }
        }

        // Last-session mode: reconnect to a known/allowed device
        if (lastSession) {
            for (device in deviceList.values) {
                val deviceCompat = UsbDeviceCompat(device)
                if (settings.isConnectingDevice(deviceCompat)) {
                    if (usbManager.hasPermission(device)) {
                        AppLog.i("Found known USB device with permission: ${deviceCompat.uniqueName}. Switching to accessory mode.")
                        isConnecting.set(true)
                        val usbMode = UsbAccessoryMode(usbManager)
                        if (usbMode.connectAndSwitch(device)) {
                            AppLog.i("Successfully requested switch to accessory mode for ${deviceCompat.uniqueName}. Waiting for re-enumeration...")
                            // Reset isConnecting — the AOA switch is done. The actual AAP
                            // connection will be established by onUsbAttach when the device
                            // re-enumerates in accessory mode.
                            isConnecting.set(false)
                            return
                        }
                        isConnecting.set(false)
                    } else {
                        AppLog.i("Found known USB device but no permission: ${deviceCompat.uniqueName}")
                    }
                }
            }
        }

        // Single-USB mode: if exactly one non-accessory device is present, connect to it
        if (singleUsb) {
            val nonAccessoryDevices = deviceList.values.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            if (nonAccessoryDevices.size == 1) {
                // Skip if stability check is already running for this exact device
                val deviceName = UsbDeviceCompat(nonAccessoryDevices[0]).uniqueName
                if (usbStabilityJob != null && stableDeviceName == deviceName) {
                    AppLog.i("checkAlreadyConnectedUsb: stability check already in progress for $deviceName, skipping")
                    return
                }
                startSingleUsbStabilityCheck(nonAccessoryDevices[0])
                return
            } else if (usbStabilityJob != null) {
                cancelUsbStabilityCheck()
            }
        }
    }

    private fun startSingleUsbStabilityCheck(device: UsbDevice) {
        val settings = App.provide(this).settings
        if (!settings.usbStabilityCheck) {
            performSingleUsbConnect(device)
            return
        }

        val deviceName = UsbDeviceCompat(device).uniqueName
        val timeoutMs = settings.usbStabilityTimeout * 1000L

        stableDeviceName = deviceName
        usbStabilityJob?.cancel()

        AppLog.i("USB stability: Starting ${settings.usbStabilityTimeout}s timer for $deviceName")
        Toast.makeText(this, getString(R.string.usb_device_settling), Toast.LENGTH_SHORT).show()

        usbStabilityJob = serviceScope.launch {
            delay(timeoutMs)

            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val stillPresent = usbManager.deviceList.values.any {
                UsbDeviceCompat(it).uniqueName == deviceName
            }

            if (stillPresent) {
                val dev = usbManager.deviceList.values.first {
                    UsbDeviceCompat(it).uniqueName == deviceName
                }
                AppLog.i("USB stability: Device $deviceName stable after ${settings.usbStabilityTimeout}s, connecting")
                performSingleUsbConnect(dev)
            } else {
                AppLog.i("USB stability: Device $deviceName disappeared during wait")
                cancelUsbStabilityCheck()
            }
        }
    }

    private fun performSingleUsbConnect(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Single USB auto-connect: connecting to $deviceName")
            isConnecting.set(true)
            val usbMode = UsbAccessoryMode(usbManager)
            if (usbMode.connectAndSwitch(device)) {
                AppLog.i("Successfully requested switch to accessory mode for single USB device. Waiting for re-enumeration...")
                // Reset isConnecting — the AOA switch is done. The actual AAP connection
                // will be established by onUsbAttach when the device re-enumerates in
                // accessory mode (which sets isConnecting again via handleConnectionIntent).
                isConnecting.set(false)
            } else {
                AppLog.w("Single USB auto-connect: connectAndSwitch failed for $deviceName")
                isConnecting.set(false)
            }
        } else {
            AppLog.i("Single USB auto-connect: device found but no permission")
        }
        cancelUsbStabilityCheck()
    }

    private fun cancelUsbStabilityCheck() {
        usbStabilityJob?.cancel()
        usbStabilityJob = null
        stableDeviceName = null
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, AapService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 
            0, 
            stopIntent, 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val (notificationIntent, requestCode) = if (isConnected) {
            AapProjectionActivity.intent(this).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            } to 100
        } else {
            Intent(this, com.andrerinas.headunitrevived.main.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } to 101
        }

        val contentText = if (isConnected) {
            getString(R.string.notification_projection_active)
        } else {
            getString(R.string.notification_service_running)
        }

        return NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentTitle("Headunit Revived")
            .setContentText(contentText)
            .setContentIntent(PendingIntent.getActivity(this, requestCode, 
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)))
            .addAction(R.drawable.ic_exit_to_app_white_24dp, getString(R.string.exit), stopPendingIntent)
            .build();
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(1, createNotification())
    }

    override fun onDestroy() {
        AppLog.i("AapService destroying...");
        isDestroying = true
        isConnecting.set(false)
        stopForeground(true)
        stopWirelessServer();
        wifiDirectManager?.stop();
        cancelUsbStabilityCheck()
        serviceJob.cancel();
        onDisconnect();
        nightModeManager?.stop()
        unregisterReceiver(nightModeUpdateReceiver)
        unregisterReceiver(disconnectReceiver)
        unregisterReceiver(usbReceiver);
        uiModeManager.disableCarMode(0);
        super.onDestroy();
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8.0+ requirements: call startForeground as early as possible
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }

        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLog.i("Stop action received.");
            isDestroying = true
            if (isConnected) {
                transport.stop()
            }
            stopForeground(true)
            stopSelf();
            return START_NOT_STICKY;
        }

        when (intent?.action) {
            ACTION_START_SELF_MODE -> {
                startSelfMode();
            }
            ACTION_START_WIRELESS -> {
                startWirelessServer();
            }
            ACTION_START_WIRELESS_SCAN -> {
                val mode = App.provide(this).settings.wifiConnectionMode
                startDiscovery(oneShot = (mode != 2))
            }
            ACTION_STOP_WIRELESS -> {
                stopWirelessServer();
            }
            ACTION_DISCONNECT -> {
                AppLog.i("Disconnect action received.");
                if (isConnected) {
                    transport.stop()
                }
            }
            ACTION_RETRY_CONNECTION -> {
                retryConnection()
            }
            ACTION_RESET_USB -> {
                resetUsbAndReconnect()
            }
            ACTION_REQUEST_NIGHT_MODE_UPDATE -> {
                AppLog.i("Night mode update action received.");
                nightModeManager?.resendCurrentState()
            }
            ACTION_CHECK_USB -> {
                AppLog.i("ACTION_CHECK_USB received")
                checkAlreadyConnectedUsb();
            }
            else -> {
                if (intent?.action == null || intent.action == Intent.ACTION_MAIN) {
                    checkAlreadyConnectedUsb()
                }
                handleConnectionIntent(intent);
            }
        }
        return START_STICKY;
    }

    private fun handleConnectionIntent(intent: Intent?) {
        val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0;
        if (connectionType == 0) return ;

        val connectionTypeName = if (connectionType == TYPE_USB) "USB" else "WiFi"
        AppLog.i("handleConnectionIntent: type=$connectionTypeName, isConnected=$isConnected, isConnecting=${isConnecting.get()}")

        // Ensure old connection is closed!
        accessoryConnection?.disconnect()
        accessoryConnection = connectionFactory(intent, this);

        if (accessoryConnection == null) {
            AppLog.e("Cannot create connection from intent");
            return;
        }

        when (connectionType) {
            TYPE_USB -> {
                val device = DeviceIntent(intent).device;
                pendingConnectionType = Settings.CONNECTION_TYPE_USB;
                pendingConnectionIp = "";
                pendingConnectionUsbDevice = if (device != null) UsbDeviceCompat.getUniqueName(device) else "";
            }
            TYPE_WIFI -> {
                pendingConnectionType = Settings.CONNECTION_TYPE_WIFI;
                pendingConnectionIp = intent?.getStringExtra(EXTRA_IP) ?: "";
                pendingConnectionUsbDevice = "";
            }
        }

        // Capture the connection instance so we pass the exact object to the result handler
        val conn = accessoryConnection;
        val attemptId = connectionAttemptId.incrementAndGet();

        serviceScope.launch {
            isConnecting.set(true)
            var retryCount = 0
            val maxRetries = 3
            var success = false

            while (retryCount <= maxRetries && !success) {
                if (retryCount > 0) {
                    AppLog.i("Retrying USB connection (attempt ${retryCount + 1}/$maxRetries)...")
                    delay(1500)
                }

                success = withContext(Dispatchers.IO) {
                    conn?.connect() ?: false
                }

                if (success || connectionType != TYPE_USB) break
                retryCount++
            }

            onConnectionResult(success, attemptId, conn)
        }
    }

    private var networkDiscovery: NetworkDiscovery? = null

    private fun startWirelessServer() {
        if (wirelessServer != null) return
        wirelessServer = WirelessServer().apply { start() }
        
        startDiscovery()
    }

    private fun startDiscovery(oneShot: Boolean = false) {
        if (isConnected || isConnecting.get() || (wirelessServer == null && !oneShot)) return

        // Ensure old discovery is stopped/cleaned
        networkDiscovery?.stop()
        isScanning = true
        sendBroadcast(Intent(ACTION_SCAN_STARTED))

        networkDiscovery = NetworkDiscovery(this, object : NetworkDiscovery.Listener {
            override fun onServiceFound(ip: String, port: Int, socket: java.net.Socket?) {
                if (isConnected) {
                    try { socket?.close() } catch (e: Exception) {}
                    return
                }

                if (port == 5277) {
                    // Headunit Server detected -> We must connect actively
                    AppLog.i("Auto-connecting to Headunit Server at $ip:$port (reusing socket)")
                    pendingSocket = socket
                    val intent = Intent(this@AapService, AapService::class.java).apply {
                        putExtra(EXTRA_IP, ip)
                        putExtra(EXTRA_CONNECTION_TYPE, TYPE_WIFI)
                    }
                    handleConnectionIntent(intent)
                } else if (port == 5289) {
                    // Wifi Launcher detected -> Triggered
                    AppLog.i("Triggered Wifi Launcher at $ip:$port.")
                }
            }

            override fun onScanFinished() {
                isScanning = false
                sendBroadcast(Intent(ACTION_SCAN_FINISHED))
                if (oneShot) {
                    AppLog.i("One-shot scan finished.")
                    return
                }
                // Schedule next scan in 10s
                serviceScope.launch {
                    delay(10000)
                    if (wirelessServer != null && !isConnected) {
                        startDiscovery()
                    }
                }
            }
        })
        networkDiscovery?.startScan()
    }

    private fun stopWirelessServer() {
        networkDiscovery?.stop()
        networkDiscovery = null
        wirelessServer?.stopServer()
        wirelessServer = null

        isScanning = false
        sendBroadcast(Intent(ACTION_SCAN_FINISHED))
    }

    private inner class WirelessServer : Thread() {
        private var serverSocket: ServerSocket? = null;
        private var nsdManager: NsdManager? = null;
        private var registrationListener: NsdManager.RegistrationListener? = null;
        private var running = true;

        override fun run() {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager;
            registerNsd();

            try {
                serverSocket = ServerSocket(5288).apply { reuseAddress = true };
                AppLog.i("Wireless Server listening on port 5288");
                
                try {
                    val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        val addresses = iface.inetAddresses
                        while (addresses.hasMoreElements()) {
                            val addr = addresses.nextElement()
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                AppLog.i("Interface: ${iface.name}, IP: ${addr.hostAddress}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLog.e("Error logging interfaces", e)
                }

                while (running) {
                    val clientSocket = serverSocket?.accept();
                    if (clientSocket != null) {
                        AppLog.i("Wireless client connected: ${clientSocket.inetAddress}");
                        
                        serviceScope.launch {
                            if (isConnected) {
                                AppLog.w("Already connected, dropping wireless client");
                                withContext(Dispatchers.IO) {
                                    try { clientSocket.close() } catch (e: Exception) {}
                                }
                            } else {
                                AppLog.i("Wireless client accepted from ${clientSocket.inetAddress}. Initializing connection...");
                                pendingConnectionType = Settings.CONNECTION_TYPE_WIFI;
                                pendingConnectionIp = clientSocket.inetAddress.hostAddress ?:"";
                                pendingConnectionUsbDevice = "";

                                // Prepare and capture the connection instance
                                val conn = SocketAccessoryConnection(clientSocket, this@AapService);
                                accessoryConnection = conn;

                                // mark this attempt before starting the blocking connect
                                val attemptId = connectionAttemptId.incrementAndGet();
                                
                                val success = withContext(Dispatchers.IO) {
                                    conn.connect()
                                }
                                AppLog.i("Wireless Socket connect() result: $success");

                                onConnectionResult(success, attemptId, conn);
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) AppLog.e("Wireless server error", e);
            } finally {
                unregisterNsd();
            }
        }

        private fun registerNsd() {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless";
                serviceType = "_aawireless._tcp";
                port = 5288;
            };
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("NSD Registered: ${info.serviceName}");
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Reg Fail: $err");
                override fun onServiceUnregistered(info: NsdServiceInfo) = AppLog.i("NSD Unregistered");
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Unreg Fail: $err");
            };
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        }

        private fun unregisterNsd() {
            registrationListener?.let { nsdManager?.unregisterService(it) };
            registrationListener = null;
        }

        fun stopServer() {
            running = false;
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    private fun startSelfMode() {
        startWirelessServer();

        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager;
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager.activeNetwork else null;
        val networkToUse = activeNetwork ?: createFakeNetwork(99999);
        val fakeWifiInfo = createFakeWifiInfo();

        val magicalIntent = Intent().apply {
            setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity");
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            putExtra("PARAM_HOST_ADDRESS", "127.0.0.1");
            putExtra("PARAM_SERVICE_PORT", 5288);
            networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) };
            fakeWifiInfo?.let { putExtra("wifi_info", it) };
        };

        try {
            AppLog.i("Launching AA Wireless Startup...");
            startActivity(magicalIntent);
        } catch (e: Exception) {
            AppLog.e("Failed to launch AA", e);
            Toast.makeText(this, getString(R.string.failed_start_android_auto), Toast.LENGTH_SHORT).show();
        }
    }

    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain();
        return try {
            parcel.writeInt(netId);
            parcel.setDataPosition(0);
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>;
            creator.createFromParcel(parcel) as Parcelable;
        } catch (e: Exception) { null } finally { parcel.recycle() }
    }

    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo");
            val wifiInfo = wifiInfoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as Parcelable;
            try {
                wifiInfoClass.getDeclaredField("mSSID").apply { isAccessible = true }.set(wifiInfo, "\"Headunit-Fake-Wifi\"");
            } catch (e: Exception) {}
            wifiInfo;
        } catch (e: Exception) { null }
    }

    private suspend fun onConnectionResult(success: Boolean, attemptId: Int, connection: AccessoryConnection?) {
        try {
            if (attemptId != connectionAttemptId.get()) {
                AppLog.w("onConnectionResult: stale attempt $attemptId, current ${connectionAttemptId.get()}");
                return;
            }
            val activeConnection = connection ?: run {
                AppLog.w("onConnectionResult: accessoryConnection cleared before transport start (attempt $attemptId)");
                return;
            }

            if (success) {
                reset();
                val transportStarted = withContext(Dispatchers.IO) {
                    transport.start(activeConnection);
                }

                if (transportStarted) {
                    isConnected = true;
                    updateNotification();
                    sendBroadcast(ConnectedIntent());
                    
                    // Create MediaSession to get higher audio priority
                    mediaSession = MediaSessionCompat(this@AapService, "HeadunitRevived").apply {
                        isActive = true
                    }

                    transport.onAudioFocusStateChanged = { isPlaying ->
                        updateMediaSessionState(isPlaying)
                    }

                    // Sync current night mode state immediately after connection
                    nightModeManager?.resendCurrentState()
                    
                    if (pendingConnectionType.isNotEmpty()) {
                        val settings = App.provide(this).settings;
                        settings.saveLastConnection(
                            type = pendingConnectionType,
                            ip = pendingConnectionIp,
                            usbDevice = pendingConnectionUsbDevice
                        );
                        AppLog.i("Saved last connection: type=$pendingConnectionType, ip=$pendingConnectionIp, usb=$pendingConnectionUsbDevice");
                        pendingConnectionType = "";
                        pendingConnectionIp = "";
                        pendingConnectionUsbDevice = "";
                    }

                    val aapIntent = AapProjectionActivity.intent(this@AapService).apply {
                        putExtra(AapProjectionActivity.EXTRA_FOCUS, true);
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    };
                    startActivity(aapIntent);
                } else {
                    stopSelf();
                }
            }
        } finally {
            isConnecting.set(false)
        }
    }

    private fun onDisconnect(isClean: Boolean = false) {
        isConnected = false;
        isConnecting.set(false)
        cancelUsbStabilityCheck()
        if (!isDestroying) {
            updateNotification();
        }
        sendBroadcast(DisconnectIntent(isClean));
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        reset();
        accessoryConnection?.disconnect();
        accessoryConnection = null;
        // Invalidate any in-flight attempts
        connectionAttemptId.incrementAndGet();

        if (wirelessServer != null) {
            AppLog.i("AapService: Disconnected. Restarting discovery loop in 2s...");
            serviceScope.launch {
                delay(2000);
                if (!isConnected) startDiscovery();
            }
        } else if (!isClean) {
             val mode = App.provide(this).settings.wifiConnectionMode
             val lastType = App.provide(this).settings.lastConnectionType
             if (mode == 1 && lastType == Settings.CONNECTION_TYPE_WIFI) { // Auto Mode, WiFi only
                 AppLog.i("AapService: Unclean WiFi disconnect in Auto Mode. Retrying discovery in 2s...");
                 serviceScope.launch {
                     delay(2000);
                     if (!isConnected) startDiscovery(oneShot = true);
                 }
             }
        }
    }

    private fun retryConnection() {
        val settings = App.provide(this).settings
        val type = settings.lastConnectionType
        val ip = settings.lastConnectionIp
        val usbDeviceName = settings.lastConnectionUsbDevice
        val maxAttempts = settings.maxAutoRetryAttempts

        AppLog.i("retryConnection: type=$type, ip=$ip, usb=$usbDeviceName, maxAttempts=$maxAttempts")

        if (isConnected) {
            onDisconnect()
        }

        isConnecting.set(true)
        serviceScope.launch {
            try {
                for (attempt in 1..maxAttempts) {
                    if (isConnected || isDestroying) break

                    val delayMs = if (attempt == 1) 1000L else 3000L
                    AppLog.i("retryConnection: attempt $attempt/$maxAttempts, waiting ${delayMs}ms")
                    delay(delayMs)

                    if (type == Settings.CONNECTION_TYPE_WIFI && ip.isNotEmpty()) {
                        AppLog.i("retryConnection: Reconnecting to WiFi $ip (attempt $attempt/$maxAttempts)")
                        handleConnectionIntent(createIntent(ip, this@AapService))
                    } else if (type == Settings.CONNECTION_TYPE_USB) {
                        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                        val accessoryDevice = usbManager.deviceList.values.firstOrNull {
                            UsbDeviceCompat.isInAccessoryMode(it)
                        }
                        if (accessoryDevice != null) {
                            AppLog.i("retryConnection: Found accessory-mode device, reconnecting (attempt $attempt/$maxAttempts)")
                            handleConnectionIntent(createIntent(accessoryDevice, this@AapService))
                        } else if (usbDeviceName.isNotEmpty()) {
                            val knownDevice = usbManager.deviceList.values.firstOrNull {
                                UsbDeviceCompat(it).uniqueName == usbDeviceName
                            }
                            if (knownDevice != null) {
                                AppLog.i("retryConnection: Found known device $usbDeviceName, switching to accessory mode (attempt $attempt/$maxAttempts)")
                                val usbMode = UsbAccessoryMode(usbManager)
                                usbMode.connectAndSwitch(knownDevice)
                            } else {
                                AppLog.w("retryConnection: USB device $usbDeviceName not found (attempt $attempt/$maxAttempts)")
                                continue
                            }
                        }
                    }

                    // Wait up to 5s for connection result
                    for (i in 1..10) {
                        delay(500)
                        if (isConnected) break
                    }
                    if (isConnected) break
                }

                if (!isConnected) {
                    AppLog.w("retryConnection: All $maxAttempts attempts failed")
                }
            } catch (e: CancellationException) {
                AppLog.i("retryConnection: cancelled")
                throw e
            } finally {
                if (!isConnected) isConnecting.set(false)
            }
        }
    }

    private fun resetUsbAndReconnect() {
        AppLog.i("resetUsbAndReconnect: Starting USB reset")

        if (isConnected) {
            onDisconnect()
        }

        val settings = App.provide(this).settings
        val stabilityEnabled = settings.usbStabilityCheck
        val stabilityThresholdMs = if (stabilityEnabled) settings.usbStabilityTimeout * 1000L else 0L

        isConnecting.set(true)
        serviceScope.launch {
            try {
                delay(1500) // Wait for cleanup

                if (stabilityEnabled) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AapService, getString(R.string.usb_device_settling), Toast.LENGTH_SHORT).show()
                    }
                }

                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                var localStableDeviceName: String? = null
                var stableSince = 0L
                val startTime = SystemClock.elapsedRealtime()
                val TIMEOUT = 90_000L

                while (SystemClock.elapsedRealtime() - startTime < TIMEOUT && !isConnected) {
                    val device = usbManager.deviceList.values.firstOrNull {
                        !UsbDeviceCompat.isInAccessoryMode(it)
                    }

                    if (device != null) {
                        val name = UsbDeviceCompat(device).uniqueName

                        if (!stabilityEnabled) {
                            AppLog.i("resetUsbAndReconnect: Device $name found, connecting immediately (stability check off)")
                            val usbMode = UsbAccessoryMode(usbManager)
                            usbMode.connectAndSwitch(device)
                            break
                        }

                        if (name == localStableDeviceName) {
                            if (SystemClock.elapsedRealtime() - stableSince >= stabilityThresholdMs) {
                                AppLog.i("resetUsbAndReconnect: Device $name stable for ${stabilityThresholdMs}ms, connecting")
                                val usbMode = UsbAccessoryMode(usbManager)
                                usbMode.connectAndSwitch(device)
                                break
                            }
                        } else {
                            localStableDeviceName = name
                            stableSince = SystemClock.elapsedRealtime()
                            AppLog.i("resetUsbAndReconnect: New device detected: $name, waiting for stability (${settings.usbStabilityTimeout}s)")
                        }
                    } else {
                        if (localStableDeviceName != null) {
                            AppLog.i("resetUsbAndReconnect: Device disappeared, resetting stability counter")
                        }
                        localStableDeviceName = null
                        stableSince = 0
                    }

                    delay(1500)
                }

                if (!isConnected && localStableDeviceName == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@AapService,
                            getString(R.string.reset_usb_timeout),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: CancellationException) {
                AppLog.i("resetUsbAndReconnect: cancelled")
                throw e
            } finally {
                if (!isConnected) isConnecting.set(false)
            }
        }
    }

    private fun reset() {
        App.provide(this).resetTransport();
        serviceScope.launch(Dispatchers.IO) {
            App.provide(this@AapService).audioDecoder.stop();
            App.provide(this@AapService).videoDecoder.stop("AapService::reset");
        }
    }

    override fun onUsbDetach(device: UsbDevice) {
        val detachedName = UsbDeviceCompat(device).uniqueName
        if (stableDeviceName == detachedName) {
            AppLog.i("USB stability: Tracked device $detachedName detached, resetting timer")
            cancelUsbStabilityCheck()
        }

        if (accessoryConnection is UsbAccessoryConnection) {
            if ((accessoryConnection as UsbAccessoryConnection).isDeviceRunning(device)) {
                onDisconnect();
            }
        }
    }

    override fun onUsbAttach(device: UsbDevice) {
        val deviceName = UsbDeviceCompat(device).uniqueName
        AppLog.i("onUsbAttach: device=$deviceName, isConnected=$isConnected, isConnecting=${isConnecting.get()}")

        if (isConnected) {
            AppLog.i("onUsbAttach: already connected, skipping")
            return
        }

        // If the attached device is already in accessory mode, connect directly.
        // This handles the re-enumeration after connectAndSwitch() where the phone
        // detaches (e.g. Xiaomi 2717:FF40) and re-appears as a Google AOA device
        // (18D1:2D00). Without this, checkAlreadyConnectedUsb() would skip because
        // isConnecting is still true from the AOA mode switch phase.
        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            AppLog.i("onUsbAttach: Accessory-mode device $deviceName attached, connecting directly")
            handleConnectionIntent(createIntent(device, this))
            return
        }

        val settings = App.provide(this).settings
        if (settings.autoConnectLastSession || settings.autoConnectSingleUsbDevice) {
            checkAlreadyConnectedUsb()
        }
    }
    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {}

    companion object {
        var isConnected = false;
        var selfMode = false;
        var isScanning = false;
        private val isConnecting = AtomicBoolean(false)
        var pendingSocket: java.net.Socket? = null;
        const val ACTION_START_SELF_MODE = "com.andrerinas.headunitrevived.ACTION_START_SELF_MODE";
        const val ACTION_START_WIRELESS = "com.andrerinas.headunitrevived.ACTION_START_WIRELESS";
        const val ACTION_START_WIRELESS_SCAN = "com.andrerinas.headunitrevived.ACTION_START_WIRELESS_SCAN";
        const val ACTION_STOP_WIRELESS = "com.andrerinas.headunitrevived.ACTION_STOP_WIRELESS";
        const val ACTION_CHECK_USB = "com.andrerinas.headunitrevived.ACTION_CHECK_USB";
        const val ACTION_SCAN_STARTED = "com.andrerinas.headunitrevived.ACTION_SCAN_STARTED"
        const val ACTION_SCAN_FINISHED = "com.andrerinas.headunitrevived.ACTION_SCAN_FINISHED"
        const val ACTION_STOP_SERVICE = "com.andrerinas.headunitrevived.ACTION_STOP_SERVICE";
        const val ACTION_DISCONNECT = "com.andrerinas.headunitrevived.ACTION_DISCONNECT";
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "com.andrerinas.headunitrevived.ACTION_REQUEST_NIGHT_MODE_UPDATE"
        const val ACTION_RETRY_CONNECTION = "com.andrerinas.headunitrevived.ACTION_RETRY_CONNECTION"
        const val ACTION_RESET_USB = "com.andrerinas.headunitrevived.ACTION_RESET_USB"
        private const val TYPE_USB = 1;
        private const val TYPE_WIFI = 2;
        private const val EXTRA_CONNECTION_TYPE = "extra_connection_type";
        private const val EXTRA_IP = "extra_ip";

        fun createIntent(device: UsbDevice, context: Context): Intent {
            return Intent(context, AapService::class.java).apply {
                putExtra(UsbManager.EXTRA_DEVICE, device);
                putExtra(EXTRA_CONNECTION_TYPE, TYPE_USB);
            };
        }

        fun createIntent(ip: String, context: Context): Intent {
            return Intent(context, AapService::class.java).apply {
                putExtra(EXTRA_IP, ip);
                putExtra(EXTRA_CONNECTION_TYPE, TYPE_WIFI);
            };
        }

        private fun connectionFactory(intent: Intent?, context: Context): AccessoryConnection? {
            val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0;
            if (connectionType == TYPE_USB) {
                val device = DeviceIntent(intent).device ?: return null;
                return UsbAccessoryConnection(context.getSystemService(Context.USB_SERVICE) as UsbManager, device);
            } else if (connectionType == TYPE_WIFI) {
                val ip = intent?.getStringExtra(EXTRA_IP) ?: "";

                val socket = pendingSocket
                pendingSocket = null // consume
                if (socket != null && socket.isConnected && socket.inetAddress.hostAddress == ip) {
                    AppLog.i("Reusing discovery socket for connection to $ip")
                    return SocketAccessoryConnection(socket, context)
                }

                return SocketAccessoryConnection(ip, 5277, context);
            }
            return null;
        }
    }
}
