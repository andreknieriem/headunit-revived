package com.andrerinas.openheadunit.connection.wifi.direct

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeHandoffPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.wifi.FiveGhzChannelPolicy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBssidPolicy
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
import com.andrerinas.openheadunit.utils.InterfaceMacReader
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket



class WifiDirectManager(private val context: Context) : WifiP2pManager.ConnectionInfoListener, WifiP2pManager.GroupInfoListener {

    private companion object {
        private const val MAX_NATIVE_5GHZ_CREATE_RETRIES = 4
        private const val MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES = 2
        private const val MAX_NATIVE_STANDARD_CREATE_RETRIES = 3

        /**
         * How often to repeat that this unit will not create a group.
         *
         * The same interval, and for the same reason, as `SoftApCredentialsProvider`'s no-access-point
         * report: often enough that any window of a busy unit's log carries it, rare enough that a
         * user can dismiss the banner it raises.
         */
        private const val GROUP_REFUSAL_REPORT_INTERVAL_MS = 60_000L
        private const val NATIVE_GROUP_MODE_UNKNOWN = "unknown"
        private const val NATIVE_GROUP_MODE_5GHZ_REQUESTED = "5GHz requested"
        private const val NATIVE_GROUP_MODE_24GHZ_REQUESTED = "2.4GHz requested"
        private const val NATIVE_GROUP_MODE_STANDARD_FALLBACK = "standard fallback"
        private const val NATIVE_GROUP_MODE_STANDARD_LEGACY = "standard (no 5GHz API)"
        // Native AA join recovery: if no phone joins the quiet-host group within this window,
        // tear it down and recreate a fresh one (bounded), dropping the forced 5GHz band after
        // a couple of tries. 60s not 30s — a live BT reconnect was observed taking ~35s even
        // when working, so 30s left no margin.
        private const val NATIVE_JOIN_TIMEOUT_MS = 60000L
        private const val MAX_NATIVE_JOIN_RECREATES = 4
        private const val NATIVE_FORCE_STANDARD_AFTER = 2
    }

    @Volatile private var manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isGroupOwner = false
    private var isConnected = false
    // Fields for checkStuckRetryBurst() below: track how tightly consecutive
    // CONNECTION_CHANGED broadcasts repeat, to detect a stuck retry loop.
    private var lastConnChangedElapsedMs = 0L
    private var tightBurstCount = 0
    private val burstGapMs = 800L
    private val burstTriggerCount = 5
    @Volatile private var isGroupCreatingOrCreated = false

    /**
     * When we last asked the P2P framework for something that can reload the interface, so
     * [P2pStateChangePolicy] can tell the DISABLED/ENABLED pair that follows from the user really
     * toggling WiFi Direct. Stamped by [markP2pRequest] at every such call on the bring-up path;
     * a new one that does not stamp it puts the loop back.
     */
    @Volatile private var lastP2pRequestAtMs = 0L

    // Guards against two concurrent checkGroupAndCreate() runs racing on the same teardown
    // (makeVisible() can be invoked twice back to back for one UI action). Cleared by a
    // bounded safety timeout in case a call site misses its own reset.
    @Volatile private var checkGroupAndCreateInFlight = false
    // Whether a phone has actually joined the group (not just that a group exists).
    // discoveryRunnable below re-advertises while this is false, and restarts if a
    // joined client disconnects.
    private var isClientConnected = false
    private val discoveryRunnable = object : Runnable {
        override fun run() {
            if (!isClientConnected) {
                // Skip while a teardown/recreate is in flight (reuse path or the stuck-retry
                // self-heal) — the group is disappearing/reforming underneath us, so a
                // discoverPeers() call here is wasted at best.
                if (!checkGroupAndCreateInFlight) {
                    startDiscovery()
                }
                handler.postDelayed(this, 10000L) // Repeat every 10s to stay visible
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var localDeviceAddress: String? = null
    private var lastKnownBssid: String? = null

    /**
     * The interface [lastKnownBssid] was read from. A P2P interface's MAC changes with the group on
     * plenty of devices, so a cached address from a different interface describes a network that no
     * longer exists and no beacon carries.
     */
    private var lastKnownBssidIface: String? = null

    /** The last SSID the BSSID source dump was printed for; group info arrives several times each. */
    private var lastBssidDumpSsid: String? = null
    private var isReceiverRegistered = false
    private var discoveredInterface: String? = null
    private var nativeGroupCreationMode = NATIVE_GROUP_MODE_UNKNOWN
    private var native5GhzBandMismatchRetries = 0

    /**
     * The band the current Native AA group was asked for. Only 5 GHz can be mismatched: a group
     * put on 2.4 GHz on purpose is on the band it was asked for, so it must not be remade.
     */
    private var nativeRequestedBand = NativeGroupBandPolicy.Band.UNSPECIFIED

    /**
     * True while a 5 GHz operating-channel restriction is in place on a pre-Q device. It lives in the
     * supplicant rather than here, so it has to be cleared deliberately - see [WifiP2pChannelCompat].
     */
    @Volatile
    private var legacyChannelRestrictionApplied = false

    /**
     * How far down [P2pOperatingChannelPolicy.attemptChannels] this bring-up has got.
     *
     * An index rather than the single "already fell back" flag it used to be, because the ladder
     * now names 2.4 GHz on its way down instead of going straight to letting the driver choose. A
     * unit that cannot host a group owner on the band it was given a frequency list for fails
     * outright, so each rung has to be offered in turn and the restriction cleared only once they
     * are spent.
     */
    private var legacyChannelAttempt = 0

    /**
     * True once this unit has let a `setWifiP2pChannels` request go unanswered.
     *
     * The request is the only pre-Q band lever, and on a driver that reloads the P2P interface to
     * apply it the pending listener is dropped, so [WifiP2pChannelCompat] has to time out instead.
     * Asking again costs that timeout on every bring-up and cannot produce a different answer, so
     * the rest of the session goes straight to creating the group on the driver's own channel.
     * Cleared by [stop] with the ladder, so a mode change asks once more.
     */
    private var legacyChannelRequestUnanswered = false

    /**
     * When the group refusal was last reported, so a unit refusing every attempt says so at a
     * readable rate rather than on each one.
     *
     * The retry ladder gives up several times a minute while the phone keeps re-dialling, and each
     * report both writes an ERROR line and re-stamps the standing record. Re-stamping is what brings
     * the main-screen banner back after a dismissal, so at that rate the user could not dismiss it
     * at all. Reset by [stop] rather than by a bring-up, so one refusal is reported per mode.
     */
    private var lastGroupRefusalReportAtMs = 0L

    /**
     * Set once a pinned 5 GHz channel has failed its whole retry budget on API 29+.
     *
     * A pinned frequency is forced all the way down to wpa_supplicant, which fails the group rather
     * than choosing elsewhere, so a channel this unit will not host costs the connection and not
     * just the channel. From here on the bring-up asks for the band instead. Cleared by [stop] and
     * not by [startNativeAaQuietHost], for the same reason [legacyChannelAttempt] is: the handshake
     * refreshes every ten seconds and each refresh lands back in that method.
     */
    @Volatile
    private var pinnedChannelAbandoned = false

    /**
     * The frequency this group was asked for, or 0 where the band was asked for instead.
     *
     * The request rather than the setting, because the setting can be changed mid-session and the
     * line that reports it sits beside the frequency the group actually came up on - which is the
     * one comparison this whole choice exists to let a reader make.
     */
    @Volatile
    private var nativeRequestedFrequency = 0

    /**
     * Bumped by [stop]. Every P2P callback that continues into another framework call captures this
     * first and gives up if it has moved, because [stop] can cancel posted runnables but nothing can
     * cancel an `ActionListener` the framework is already holding - and those continuations create
     * groups. Without the fence, a user exit that lands mid-recovery removes the group and then a
     * continuation puts a new one up with no receiver, no watchdog and nobody expecting it, which is
     * exactly what the exit was for.
     */
    @Volatile
    private var generation = 0

    /** One token per checkGroupAndCreate run, so a stale safety timer cannot clear a later run's guard. */
    private var checkGroupAndCreateToken = 0

    /**
     * Bumped whenever the group these credentials describe stops being the current one.
     *
     * The delivery thread below waits up to 15s for an IP and then hands the SSID, passphrase and
     * BSSID it captured at spawn to the handshake. Nothing cancels it, and its IP fallback means it
     * always produces something - so a group torn down mid-wait still delivers, overwriting the live
     * group's credentials with a dead group's. The phone then joins a network that no longer exists
     * and sits on "Obtaining IP address" forever, having been told the truth about the wrong group.
     */
    @Volatile
    private var credentialsEpoch = 0

    /** The group the join watchdog is currently armed for, so repeat callbacks cannot push it out. */
    private var nativeJoinWatchdogSsid: String? = null

    /**
     * True when [stop] has run since [gen] was captured, meaning this continuation belongs to a
     * session that is over and must not create anything.
     */
    private fun supersededByStop(gen: Int, what: String): Boolean {
        if (gen == generation) return false
        AppLog.i("WifiDirectManager: $what abandoned - the manager was stopped while it was in flight.")
        return true
    }

    /** Says the frequency is unreadable once per group, not once per group-info callback. */
    private var lastFrequencyUnreadableSsid: String? = null

    /**
     * SSID of the last group reported as being on a channel most phones cannot join, so the several
     * [onGroupInfoAvailable] callbacks a single group produces are said once.
     *
     * The SSID and not the interface or the BSSID: Android generates a fresh DIRECT-xy-… per group,
     * it is populated on the very first callback, and it is never privacy-masked. `interface` is
     * null until an IP is up and the BSSID is often 02:00:00:00:00:00 here, so either would both
     * split one group across two identities and merge two groups into one.
     */
    private var lastUnfriendlyChannelSsid: String? = null
    private var lastCoexistenceKey: String? = null
    private var lastNativeGroupStatusMessage: String? = null

    // Native AA join recovery state. The watchdog fires if the phone never joins our quiet-host
    // group; nativeRecreateCount bounds how many times we recreate before giving up.
    private var nativeRecreateCount = 0
    private val nativeJoinWatchdog = Runnable {
        if (isClientConnected) return@Runnable
        if (isNativeSessionConnected?.invoke() == true) {
            // Native joins are out-of-band over Bluetooth, not P2P invitation, so clientList (and
            // isClientConnected) can stay empty forever even on a fully working session.
            AppLog.i("WifiDirectManager: Native AA join watchdog fired but a session is already connected — cancelling recovery, not tearing down a working connection.")
            cancelNativeJoinWatchdog()
            nativeRecreateCount = 0
            return@Runnable
        }
        if (isNativeHandshakeInFlight?.invoke() == true) {
            // A handshake is exchanging credentials right now, or the phone is still joining on
            // credentials we just handed it; recreating here would hand out a new SSID mid-flight.
            AppLog.i("WifiDirectManager: Native AA join watchdog fired but a Bluetooth handshake or handoff is in flight — deferring recovery.")
            armNativeJoinWatchdog()
            return@Runnable
        }
        recoverNativeGroup("no phone joined within ${NATIVE_JOIN_TIMEOUT_MS / 1000}s")
    }

    private var onCredentialsReady: ((ssid: String, psk: String, ip: String, bssid: String) -> Unit)? = null
    // Set by AapService: whether NativeAaHandshakeManager has a live handshake in progress *or*
    // a delivered handoff still settling (the phone associating/doing DHCP after Type 3), so the
    // join watchdog/self-heal never tears the group down mid-exchange or mid-join.
    private var isNativeHandshakeInFlight: (() -> Boolean)? = null
    // Set by AapService: whether a real AA session is connected - isClientConnected can't tell
    // that apart from nobody joining (see nativeJoinWatchdog above).
    private var isNativeSessionConnected: (() -> Boolean)? = null
    // Set by AapService: called right before a native group is torn down, to invalidate any
    // not-yet-captured credentials in NativeAaHandshakeManager.
    private var onNativeGroupInvalidated: (() -> Unit)? = null

    fun setCredentialsListener(callback: (String, String, String, String) -> Unit) {
        this.onCredentialsReady = callback
    }

    fun setNativeHandshakeStateProvider(provider: () -> Boolean) {
        this.isNativeHandshakeInFlight = provider
    }

    fun setNativeSessionConnectedProvider(provider: () -> Boolean) {
        this.isNativeSessionConnected = provider
    }

    fun setNativeGroupInvalidatedListener(callback: () -> Unit) {
        this.onNativeGroupInvalidated = callback
    }



    private fun markP2pRequest() {
        lastP2pRequestAtMs = System.currentTimeMillis()
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    AppLog.i("WifiDirectManager: WIFI_P2P_STATE_CHANGED_ACTION state=$state")
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        val appSettings = App.provide(context).settings
                        val commManager = App.provide(context).commManager
                        val isConnectingOrConnected = commManager.isConnected ||
                            commManager.connectionState.value is CommManager.ConnectionState.Connecting

                        val busy = isConnected || isConnectingOrConnected || isGroupCreatingOrCreated
                        if (P2pStateChangePolicy.shouldStartBringUp(busy, System.currentTimeMillis(), lastP2pRequestAtMs)) {
                            if (appSettings.wifiConnectionMode == WifiLauncherMode.HELPER && appSettings.helperConnectionStrategy == HelperStrategy.WIFI_DIRECT) {
                                AppLog.i("WifiDirectManager: P2P enabled, auto-starting WiFi Direct visibility")
                                makeVisible()
                            } else if (appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE) {
                                AppLog.i("WifiDirectManager: P2P enabled, auto-starting Native AA quiet host")
                                startNativeAaQuietHost()
                            }
                        }
                    } else if (P2pStateChangePolicy.shouldResetOnDisable(System.currentTimeMillis(), lastP2pRequestAtMs)) {
                        isGroupCreatingOrCreated = false
                        isConnected = false
                        isClientConnected = false
                        cancelNativeJoinWatchdog()
                        nativeRecreateCount = 0
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    device?.let {
                        if (App.provide(context).settings.wifiConnectionMode != WifiLauncherMode.NATIVE) {
                            AppLog.i("WifiDirectManager: Local name: ${it.deviceName}, Address: ${it.deviceAddress}")
                        }
                        AapService.wifiDirectName.value = it.deviceName
                        localDeviceAddress = it.deviceAddress
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        AppLog.i("WifiDirectManager: Connected. Requesting info...")
                        checkStuckRetryBurst()
                        // [FIX] Pre-fetch localDeviceAddress here so it's ready before
                        // onGroupInfoAvailable fires — reduces race condition window.
                        WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                            if (localDeviceAddress == null || localDeviceAddress == "00:00:00:00:00:00" || localDeviceAddress == "02:00:00:00:00:00") {
                                AppLog.d("WifiDirectManager: Pre-fetched localDeviceAddress on connect: $address")
                                localDeviceAddress = address
                            }
                        }
                        manager?.requestConnectionInfo(channel, this@WifiDirectManager)
                        AapService.scanningState.value = false
                    } else {
                        isConnected = false
                        isClientConnected = false
                        lastNativeGroupStatusMessage = null
                        isGroupCreatingOrCreated = false
                        cancelNativeJoinWatchdog()
                    }
                }
            }
        }
    }

    init {
        try {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
                AppLog.i("WifiDirectManager: Device supports WiFi Direct. Initializing...")
                manager?.let { mgr ->
                    channel = mgr.initialize(context, context.mainLooper, null)

                    WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                        AppLog.i("WifiDirectManager: requestDeviceInfo success: $address")
                        localDeviceAddress = address
                    }

                    registerReceiverIfNeeded()
                } ?: run {
                    AppLog.e("WifiDirectManager: WIFI_P2P_SERVICE manager is NULL!")
                }
            } else {
                AppLog.e("WifiDirectManager: Device does NOT report FEATURE_WIFI_DIRECT!")
            }
        } catch (e: SecurityException) {
            AppLog.w("WifiDirectManager: WiFi Direct unavailable — permission denied: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Unexpected error in init", e)
        }
    }

    // Self-heals a stuck PROV-DISC retry loop (no AAP session ever connects, so nothing
    // triggers checkGroupAndCreate()'s own teardown) by detecting how tightly
    // CONNECTION_CHANGED repeats: ~200-300ms apart when stuck vs 1.1-1.9s on a real connection.
    @SuppressLint("MissingPermission")
    private fun checkStuckRetryBurst() {
        val appSettings = App.provide(context).settings
        val isHelperP2p = appSettings.wifiConnectionMode == WifiLauncherMode.HELPER && appSettings.helperConnectionStrategy == HelperStrategy.WIFI_DIRECT
        val isNative = appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE
        if ((!isHelperP2p && !isNative) || !isGroupOwner) {
            tightBurstCount = 0
            return
        }

        val now = SystemClock.elapsedRealtime()
        val gap = now - lastConnChangedElapsedMs
        lastConnChangedElapsedMs = now

        tightBurstCount = if (gap in 1 until burstGapMs) tightBurstCount + 1 else 1

        if (tightBurstCount >= burstTriggerCount) {
            AppLog.w("WifiDirectManager: Detected $tightBurstCount CONNECTION_CHANGED repeats <${burstGapMs}ms apart — stuck retry loop against an already-consumed group. Self-healing.")
            tightBurstCount = 0
            if (isNative) {
                if (isNativeHandshakeInFlight?.invoke() == true) {
                    AppLog.i("WifiDirectManager: Native AA stuck-retry-burst detected but a Bluetooth handshake is in flight — skipping recovery.")
                    return
                }
                // Native quiet-host has no discovery loop; recreate the group (bounded), the
                // same class of self-heal as the helper path.
                recoverNativeGroup("stuck PROV-DISC retry loop")
                return
            }
            val mgr = manager
            val ch = channel
            if (mgr != null && ch != null) {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { createNewGroup(0) }
                    override fun onFailure(reason: Int) {
                        AppLog.w("WifiDirectManager: removeGroup during stuck-loop self-heal failed: ${getP2pErrorString(reason)}")
                        createNewGroup(0)
                    }
                })
            }
        }
    }

    private fun registerReceiverIfNeeded() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
            AppLog.d("WifiDirectManager: BroadcastReceiver registered.")
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Failed to register receiver", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (info.groupFormed) {
            isConnected = true
            AapService.scanningState.value = false
            isGroupOwner = info.isGroupOwner

            val goIp = info.groupOwnerAddress?.hostAddress ?: "unknown"
            AppLog.i("WifiDirectManager: Group formed. Owner: $isGroupOwner, GO IP: $goIp")

            if (isGroupOwner) {
                // [FIX] requestDeviceInfo is async — call requestGroupInfo only AFTER the callback
                // fires so that localDeviceAddress is guaranteed to be set before onGroupInfoAvailable
                // runs. This eliminates the race condition that caused empty BSSIDs on Android 12+.
                WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                    AppLog.i("WifiDirectManager: Updated localDeviceAddress via requestDeviceInfo: $address")
                    localDeviceAddress = address
                    manager?.requestGroupInfo(channel, this@WifiDirectManager)
                }
                // Fallback: if requestDeviceInfo is not supported (< API 29), call directly
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    manager?.requestGroupInfo(channel, this)
                }
            } else if (info.groupOwnerAddress != null) {
                Thread {
                    var socket: Socket? = null
                    try {
                        AppLog.i("WifiDirectManager: Pinging Phone (GO) at $goIp to announce tablet...")
                        socket = Socket()
                        socket.connect(InetSocketAddress(info.groupOwnerAddress, 5289), 2000)
                    } catch (e: Exception) {
                        AppLog.w("WifiDirectManager: Ping to GO failed: ${e.message}")
                    } finally {
                        try { socket?.close() } catch (e: Exception) {}
                    }
                }.start()
            }
        } else {
            AppLog.d("WifiDirectManager: onConnectionInfoAvailable: group not formed yet")
            isConnected = false
            isGroupOwner = false
        }
    }

    private var groupInfoRetries = 0

    @SuppressLint("MissingPermission")
    override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
        val appSettings = App.provide(context).settings
        if (group != null) {
            // [FIX] Check if Location Services (GPS) are enabled.
            // On Android 10+, BSSID is often masked if GPS is OFF.
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val isNetworkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                AppLog.i("WifiDirectManager: System Location Check: GPS=$isGpsEnabled, Network=$isNetworkEnabled")
                if (!isGpsEnabled && !isNetworkEnabled) {
                    AppLog.w("WifiDirectManager: WARNING - Location Services are DISABLED. BSSID will likely be masked (00:00...)!")
                }
            } catch (e: Exception) {
                AppLog.w("WifiDirectManager: Failed to check Location Services status: ${e.message}")
            }

            groupInfoRetries = 0
            val ssid = group.networkName
            val psk = group.passphrase ?: ""
            val isOwner = group.isGroupOwner

            // [FIX] Track whether a phone client has actually joined our group.
            // If we are the Group Owner and the client list is empty, no phone has connected yet.
            // If the client list becomes non-empty, a phone joined — stop the discovery loop.
            // If the client list becomes empty again (phone disconnected), restart the loop.
            if (isOwner) {
                val clients = group.clientList
                val hadClient = isClientConnected
                isClientConnected = clients != null && clients.isNotEmpty()
                if (isClientConnected) {
                    // A phone joined — stop the native join watchdog and reset its budget.
                    cancelNativeJoinWatchdog()
                    nativeRecreateCount = 0
                }
                if (hadClient && !isClientConnected) {
                    // [BUG_FIX] Never rediscover on the Native AA path: it is a *quiet* host, the
                    // phone finds us by SSID from the credentials handed over Bluetooth, and
                    // discoverPeers() takes the group owner off-channel every 10 s. Starting that
                    // loop when a phone drops mid-DHCP leaves every retry stuck at "Obtaining IP
                    // address".
                    if (NativeHandoffPolicy.shouldRestartDiscovery(
                            nativeAaMode = isNativeAaMode(),
                            hadClient = hadClient,
                            hasClient = isClientConnected)) {
                        AppLog.i("WifiDirectManager: Client disconnected from P2P group. Restarting discovery loop.")
                        startDiscoveryLoop()
                    } else {
                        AppLog.i("WifiDirectManager: Client left the Native AA group; staying a quiet host instead of rediscovering.")
                    }
                    if (isNativeAaMode()) armNativeJoinWatchdog()
                }
            } else {
                isClientConnected = true
            }

            // [FIX] Robust interface detection. group.interface is often null on Android 11+ (hidden API)
            var iface = group.`interface`
            if (iface.isNullOrEmpty()) {
                iface = getInterfaceByIp("192.168.49.1")
                if (iface != null) {
                    AppLog.i("WifiDirectManager: Discovered interface name by IP 192.168.49.1: $iface")
                }
            }
            discoveredInterface = iface
            // [BUG_FIX] The override is checked for shape, not merely for being set. It used to be
            // taken verbatim whenever it was anything other than the unset sentinel "0", which meant
            // a mistyped address won the chain, did not match the masked-string test below, and so
            // suppressed all six fallbacks — and the failure then surfaced 30 s later at Type 3 time
            // as a message blaming location services. SoftApBssidPolicy has validated this on the
            // hotspot route since the same bug was found there; this is the other half.
            val rawOverride = appSettings.staticBSSID
            // choose() rather than isUsable(), for the normalisation: it accepts a hand-typed
            // address written with dashes or in lower case and hands back the colon-separated upper
            // case the phone is given. The hotspot route has read the override through this call
            // since it was written.
            val overrideBssid = SoftApBssidPolicy.choose(rawOverride, null, null)
            val isBssidSet = overrideBssid.isNotEmpty()

            logBssidSourceDump(group, iface, ssid, rawOverride)

            // Two sources ahead of the framework's own MAC, because both survive on a device where
            // getHardwareAddress() is masked: the group owner's BSSID where the vendor exposes it,
            // and the MAC the kernel encoded in the interface's IPv6 link-local address. Which one
            // answered is carried in bssidSource so the log can say so.
            var bssidSource = "static override"
            var bssid: String = overrideBssid
            if (!isBssidSet) {
                val ownerBssid = SoftApBssidPolicy.choose(null, getGroupOwnerBssid(group), null)
                val linkLocalBssid =
                    SoftApBssidPolicy.choose(null, InterfaceMacReader.fromIpv6LinkLocal(iface), null)
                when {
                    ownerBssid.isNotEmpty() -> {
                        bssid = ownerBssid
                        bssidSource = "getGroupOwnerBssid()"
                    }
                    linkLocalBssid.isNotEmpty() -> {
                        bssid = linkLocalBssid
                        bssidSource = "IPv6 link-local"
                        AppLog.i(
                            "WifiDirectManager: BSSID read from the IPv6 link-local address of " +
                                "${iface ?: "an access point interface"} (EUI-64): $bssid"
                        )
                    }
                    else -> {
                        bssid = getWifiDirectMac(iface)
                        bssidSource = "NetworkInterface.hardwareAddress"
                        AppLog.i(
                            "WifiDirectManager: ${iface ?: "no interface"} carries no EUI-64 IPv6 " +
                                "link-local address, so no MAC can be derived from it - this " +
                                "interface uses RFC 7217 stable-privacy addressing."
                        )
                    }
                }
            }
            if (isBssidSet) {
                AppLog.i("WifiDirectManager: Initial BSSID from App settings: $bssid")
            } else {
                if (!rawOverride.isNullOrEmpty() && rawOverride != "0") {
                    // Said out loud rather than silently ignored: the user typed something, and
                    // "your static BSSID is being ignored" is the only line that explains why the
                    // value they set is not the one in the credentials.
                    AppLog.w(
                        "WifiDirectManager: the static BSSID setting ('$rawOverride') is not a MAC " +
                            "address, so it is being ignored. Set it to six hex pairs " +
                            "(XX:XX:XX:XX:XX:XX) or clear it to detect one automatically."
                    )
                }
                AppLog.i("WifiDirectManager: Initial BSSID from $bssidSource: $bssid")
            }



            // [FIX] Robust BSSID detection for masked MACs (00:00 or 02:00)
            //
            // The tests below ask SoftApBssidPolicy rather than comparing against the two masking
            // placeholders: it checks the shape, so a source that answers with something that is
            // not an address at all - an empty string, a status word, a hostname - is rejected here
            // instead of winning the chain and being published verbatim.
            if (!SoftApBssidPolicy.isUsable(bssid)) {
                AppLog.i("WifiDirectManager: BSSID is masked. Starting fallbacks...")

                // Fallback 1: Use last known valid BSSID, but only from this same interface.
                if (SoftApBssidPolicy.isUsable(lastKnownBssid) && lastKnownBssidIface == iface) {
                    AppLog.i("WifiDirectManager: Fallback 1 - Using lastKnownBssid: $lastKnownBssid")
                    bssid = lastKnownBssid!!
                    bssidSource = "lastKnownBssid cache"
                }
                // Fallback 2: Use captured localDeviceAddress
                else if (SoftApBssidPolicy.isUsable(localDeviceAddress)) {
                    AppLog.i("WifiDirectManager: Fallback 2 - Using localDeviceAddress: $localDeviceAddress")
                    bssid = localDeviceAddress!!
                    bssidSource = "requestDeviceInfo"
                }
                // Fallback 3: Use group.owner.deviceAddress
                else {
                    val ownerAddr = group.owner?.deviceAddress
                    AppLog.i("WifiDirectManager: Fallback 3 - group.owner.deviceAddress: $ownerAddr")
                    if (SoftApBssidPolicy.isUsable(ownerAddr)) {
                        AppLog.i("WifiDirectManager: Fallback 3 - Selected group.owner.deviceAddress: $ownerAddr")
                        bssid = ownerAddr!!
                        bssidSource = "group.owner.deviceAddress"
                    } else {
                        AppLog.i("WifiDirectManager: Fallback 4 - Attempting shell/sysfs for $iface...")
                        val shellMac = getMacFromShell(iface)
                        if (SoftApBssidPolicy.isUsable(shellMac)) {
                            AppLog.i("WifiDirectManager: Fallback 4 - Selected shell/sysfs MAC: $shellMac")
                            bssid = shellMac!!
                            bssidSource = "sysfs / ip link"
                        } else {
                            // Fallback 5: Try Settings.Secure (Samsung/Pixel trick)
                            var resolved = false
                            try {
                                val secureMac = Settings.Secure.getString(context.contentResolver, "wifi_p2p_device_address")
                                if (SoftApBssidPolicy.isUsable(secureMac)) {
                                    AppLog.i("WifiDirectManager: Fallback 5 - Selected MAC from Settings.Secure: $secureMac")
                                    bssid = secureMac
                                    bssidSource = "Settings.Secure"
                                    resolved = true
                                }
                            } catch (e: Exception) {
                                AppLog.w("WifiDirectManager: Fallback 5 failed: ${e.message}")
                            }

                            // Fallback 6: Reflect over WifiP2pGroup/WifiP2pDevice hidden fields for
                            // any unmasked MAC the public getters didn't expose (some OEM privacy
                            // hardening masks NetworkInterface/deviceAddress but leaves other
                            // internal fields populated).
                            if (!resolved) {
                                val reflectedMac = getMacFromReflection(group)
                                if (SoftApBssidPolicy.isUsable(reflectedMac)) {
                                    AppLog.i("WifiDirectManager: Fallback 6 - Selected MAC via reflection: $reflectedMac")
                                    bssid = reflectedMac!!
                                    bssidSource = "field reflection"
                                } else {
                                    AppLog.w("WifiDirectManager: All fallbacks failed! BSSID is still zeroed.")
                                }
                            }
                        }
                    }
                }
            }

            if (SoftApBssidPolicy.isUsable(bssid)) {
                // Normalised once, here, so every consumer and the cache see the same upper-case
                // colon form the hotspot route already hands over.
                bssid = SoftApBssidPolicy.choose(null, bssid, null)
                lastKnownBssid = bssid
                lastKnownBssidIface = iface
            }

            // Below API 29 there is nothing to read. WifiP2pGroup gained getFrequency() in Q, and
            // before that it carries no frequency at all - the supplicant callback is handed one and
            // discards it, so there is no field for reflection to find and the old attempt to guess
            // at "frequency"/"mFrequency" could only ever return 0. Reported once per group rather
            // than left as a bare 0, because every band decision downstream reads as "unknown" here
            // and a reader needs to know that is the platform's limit and not this unit's fault.
            var frequency = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                frequency = WifiDirectCompat.getGroupFrequency(group)
            } else if (ssid != lastFrequencyUnreadableSsid) {
                lastFrequencyUnreadableSsid = ssid
                AppLog.i(
                    "WifiDirectManager: this Android (API ${Build.VERSION.SDK_INT}) does not report a " +
                        "P2P group's frequency - WifiP2pGroup only carries it from API 29 - so the band " +
                        "below is unknown rather than missing. Read it from the phone's WiFi details, or " +
                        "from wpa_supplicant's own \"P2P: Set GO freq\" line."
                )
            }

            val band = if (frequency > 4000) "5GHz" else if (frequency > 0) "2.4GHz" else "unknown"
            val channelLabel = if (WifiP2pChannelPolicy.is24GHz(frequency)) ", ${WifiP2pChannelPolicy.describe(frequency)}" else ""
            // Names the request beside the result: a group that came up on 5745 after the user asked
            // for 5180 is the whole failure this setting exists for, and one line has to answer it.
            val requestedLabel =
                if (nativeRequestedFrequency > 0) ", $nativeRequestedFrequency MHz was asked for" else ""
            AppLog.i("WifiDirectManager: onGroupInfoAvailable: SSID: $ssid, BSSID: $bssid (source=$bssidSource), GO: $isOwner, IFACE: ${iface ?: "null"}, Freq: $frequency MHz ($band$channelLabel)$requestedLabel")

            // Runs before the channel report below, which is the order the Native AA branch used to
            // impose from inside itself: a group that is about to be torn down and remade on 5GHz
            // must not first tell the user to restart their WiFi. The retry regenerates the SSID
            // every time, so the report's per-SSID dedupe would not have suppressed the repeats.
            if (isNativeAaMode() && isOwner) {
                if (frequency > 4000) {
                    native5GhzBandMismatchRetries = 0
                } else if (shouldRetryNativeGroupFor5Ghz(frequency)) {
                    native5GhzBandMismatchRetries++
                    AppLog.w("WifiDirectManager: Native AA group was requested as 5GHz but came up on $frequency MHz ($band). Recreating 5GHz group (mismatch retry $native5GhzBandMismatchRetries/$MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES).")
                    showToast("Native AA WiFi Direct started on $band. Retrying 5GHz...")
                    removeGroupAndRetryNative5Ghz()
                    return
                }
            }

            if (isOwner) {
                logStationCoexistence(ssid, frequency)

                // A group above channel 11 is up and beaconing and still invisible to most phones:
                // a client in the FCC domain associates on channels 1-11 only and will not even
                // list the SSID in a scan, so the phone reports nothing worse than "can't find the
                // network". Report it and carry on — recreating the group does not help, because
                // the channel is picked when the WiFi radio comes up, not when the group is made:
                // measured on a unit where this failed as six recreates, 2467 MHz every time. Only
                // a WiFi restart, or a country code the driver will honour, moves it.
                //
                // Said once per group, not once per callback: requestGroupInfo() is issued from
                // several places, so this runs three or four times for one group.
                if (WifiP2pChannelPolicy.isClientUnfriendly(frequency)) {
                    if (ssid != lastUnfriendlyChannelSsid) {
                        lastUnfriendlyChannelSsid = ssid
                        AppLog.e("WifiDirectManager: WiFi Direct group came up on ${WifiP2pChannelPolicy.describe(frequency)} ($frequency MHz). Carrying on, but a phone limited to channels 1-11 will not find this network: it will scan and never see the SSID. Restarting this unit's WiFi, or giving it a WiFi country code, is what moves the group off channel 12/13.")
                        showToast("WiFi Direct is on ${WifiP2pChannelPolicy.describe(frequency)}, which most phones cannot join. Restart WiFi and try again.")
                    }
                } else if (frequency > 0) {
                    lastUnfriendlyChannelSsid = null
                }
            }

            if (isNativeAaMode() && isOwner) {
                notifyNativeGroupStarted(ssid, frequency, band)
                // The group is up. If no phone joins within the window, recover (recreate fresh).
                armNativeJoinWatchdog(ssid)
            }

            if (ssid.isNotEmpty()) {
                // Wait for the IP address to be assigned to the interface
                val deliveryEpoch = credentialsEpoch
                Thread {
                    try {
                        var ip = getWifiDirectIp(iface)
                        var retries = 0
                        while (ip == null && retries < 15) {
                            AppLog.d("WifiDirectManager: Waiting for IP on interface ${iface ?: "any p2p"} (Attempt ${retries + 1}/15)...")
                            Thread.sleep(1000)
                            ip = getWifiDirectIp(iface)
                            retries++
                        }

                        // For Native AA, we almost always expect 192.168.49.1 if we are GO
                        val finalIp = ip ?: (if (isOwner) "192.168.49.1" else null)
                        if (deliveryEpoch != credentialsEpoch) {
                            AppLog.i(
                                "WifiDirectManager: not delivering credentials for $ssid - that group was " +
                                    "replaced while this was waiting for an IP, and the phone must not be " +
                                    "sent a network that no longer exists."
                            )
                        } else if (finalIp != null) {
                            AppLog.i("WifiDirectManager: SUCCESS - Providing credentials to listener. SSID=$ssid, IP=$finalIp, BSSID=$bssid")
                            onCredentialsReady?.invoke(ssid, psk, finalIp, bssid)
                        } else {
                            AppLog.e("WifiDirectManager: FAILED to get valid IP for credentials delivery.")
                        }
                    } catch (e: Exception) {
                        AppLog.e("WifiDirectManager: Error in credential delivery thread", e)
                    }
                }.start()
            } else {
                // Nothing is delivered without a name, and this used to be the one exit from
                // onGroupInfoAvailable that said nothing at all: the symptom reached the log 30 s
                // later as the handshake's generic "No WiFi credentials available", pointing at the
                // credentials wait rather than at the group that never named itself.
                AppLog.e(
                    "WifiDirectManager: the P2P group came up without a network name, so there is " +
                        "nothing to hand the phone and no credentials will be sent." +
                        // Only the Native AA join watchdog armed above recovers from this. On the
                        // other paths nothing does, and a log that promised a recreate everywhere
                        // would send the reader looking for one that never comes.
                        if (isNativeAaMode() && isOwner) " The join watchdog will recreate it." else ""
                )
            }
        } else {
            if (groupInfoRetries < 20) {
                groupInfoRetries++
                AppLog.w("WifiDirectManager: Group info was null! Retrying in 1s (Attempt $groupInfoRetries/20)...")
                handler.postDelayed({
                    channel?.let { ch ->
                        manager?.requestGroupInfo(ch, this)
                    }
                }, 1000L)
            } else {
                AppLog.e("WifiDirectManager: FATAL: Group info remained null after 20 retries.")
                groupInfoRetries = 0
            }
        }
    }

    /**
     * Reports whether this unit is also joined to an ordinary WiFi network while hosting the group.
     *
     * One radio serving a station link and a group owner at once has to divide its time between
     * them, and on a single-channel chipset that shows up as the projected video and audio going
     * dead together for a few hundred milliseconds at a time, over and over, with nothing wrong
     * anywhere in the app. It is invisible from a log otherwise, and it is not rare: a dashcam or
     * a phone hotspot the unit reconnects to on its own is enough.
     *
     * Frequencies make the diagnosis exact when both are known: the same channel is shared airtime,
     * different channels means the radio is also retuning between them. Several head units report
     * the group frequency as 0, and on those the comparison cannot be made at all - which is what
     * [StationCoexistencePolicy] is for. It describes and never prescribes: two units have now
     * been measured running clean in exactly the state the old line told them to change.
     *
     * Both arms print. The unjoined one used to return silently, which made the *good* arm of that
     * comparison the only one with a line in it and left a missing line meaning either "not joined"
     * or "the read threw". Whether the unit is joined is the single variable that separated a clean
     * session from one losing picture and sound every ten seconds on the unit that prompted this,
     * so a capture that cannot be sorted into an arm is a capture that cannot be used.
     *
     * Logged once per group and state rather than once per callback, because requestGroupInfo() is
     * issued several places, so this runs three or four times for one group.
     */
    private fun logStationCoexistence(ssid: String, groupFrequency: Int) {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo ?: return
            // supplicantState, not networkId or SSID. Both of those are redacted to -1 and
            // "<unknown ssid>" whenever the caller cannot satisfy the location gate, which on a
            // head unit is routine (the service runs without the projection activity in front),
            // so keying on either would silently report "not associated" on the newer Android
            // versions where this diagnosis is worth having. supplicantState survives redaction.
            val associated = info.supplicantState == SupplicantState.COMPLETED

            // The key carries the association state as well as the group, so a station that drops
            // or joins part-way through one group says so once more rather than staying on
            // whatever it said first. Still one line per group per state.
            val key = if (associated) "$ssid|joined" else "$ssid|alone"
            if (key == lastCoexistenceKey) return
            lastCoexistenceKey = key

            val staFrequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                info.frequency
            } else 0
            val finding = if (associated) {
                StationCoexistencePolicy.describe(staFrequency, groupFrequency)
            } else {
                StationCoexistencePolicy.describeNotAssociated(groupFrequency)
            }
            val line = "WifiDirectManager: ${finding.message}"
            when (finding.level) {
                StationCoexistencePolicy.Level.WARN -> AppLog.w(line)
                StationCoexistencePolicy.Level.INFO -> AppLog.i(line)
            }
        } catch (e: Exception) {
            AppLog.d("WifiDirectManager: Could not read station state for coexistence check: ${e.message}")
        }
    }

    /**
     * Say that this unit will not create a group, at most once per [GROUP_REFUSAL_REPORT_INTERVAL_MS].
     *
     * The station state goes on the line because it is the one fact that makes the refusal readable,
     * and the nearest other mention of it in a reporter's log is a minute earlier in a different
     * component's output.
     */
    private fun reportGroupRefusal(reasonStr: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastGroupRefusalReportAtMs != 0L &&
            now - lastGroupRefusalReportAtMs < GROUP_REFUSAL_REPORT_INTERVAL_MS
        ) return
        lastGroupRefusalReportAtMs = now
        AppLog.e(
            "WifiDirectManager: createQuietGroup failed completely! Reason: $reasonStr. " +
                describeStationForRefusal()
        )
        ConnectionIssues.raise(context, ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED)
    }

    /**
     * What this unit's own WiFi was doing when the platform refused to create a group.
     *
     * Descriptive, like [StationCoexistencePolicy], and for the same reason: a radio already serving
     * a station link is the commonest shape of this refusal but has never been measured as its
     * cause, so this states what was true and leaves the conclusion to whoever reads the log.
     * [SupplicantState] rather than the SSID because the SSID is redacted without the location gate,
     * which a head unit routinely cannot satisfy.
     */
    private fun describeStationForRefusal(): String = try {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        when {
            info == null -> "This unit's own WiFi state could not be read."
            info.supplicantState != SupplicantState.COMPLETED ->
                "This unit's WiFi is not joined to any network."
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && info.frequency > 0 ->
                "This unit's WiFi is joined to another network on ${info.frequency} MHz."
            else -> "This unit's WiFi is joined to another network."
        }
    } catch (e: Exception) {
        "This unit's own WiFi state could not be read (${e.message})."
    }

    private fun getInterfaceByIp(targetIp: String): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.hostAddress == targetIp) return iface.name
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun getWifiDirectMac(ifaceName: String?): String {
        AppLog.d("WifiDirectManager: getWifiDirectMac for interface: ${ifaceName ?: "any"}")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val mac = iface.hardwareAddress
                val macStr = if (mac != null) {
                    val sb = StringBuilder()
                    for (i in mac.indices) {
                        sb.append(String.format("%02X%s", mac[i], if (i < mac.size - 1) ":" else ""))
                    }
                    sb.toString()
                } else "null"

                AppLog.d("WifiDirectManager: Found interface: ${iface.name}, MAC: $macStr")

                // If we have a name, it must match.
                if (ifaceName != null && iface.name != ifaceName) continue

                // If we don't have a name, look for common P2P interface patterns
                if (ifaceName == null) {
                    val name = iface.name.lowercase()
                    if (!name.contains("p2p") && !name.contains("wlan") && !name.contains("ap")) continue
                }

                if (macStr != "null" && macStr != "00:00:00:00:00:00" && macStr != "02:00:00:00:00:00") {
                    AppLog.d("WifiDirectManager: Selected MAC for ${iface.name}: $macStr")
                    return macStr
                }
            }
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Error scanning network interfaces", e)
        }
        AppLog.w("WifiDirectManager: No valid MAC found in NetworkInterface scan for ${ifaceName ?: "any"}")
        return "00:00:00:00:00:00"
    }

    private fun getWifiDirectIp(ifaceName: String?): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        // Prioritize explicitly requested interface
                        if (ifaceName != null && iface.name == ifaceName) return addr.hostAddress

                        // Fallback: search for 192.168.49.1 (Standard P2P GO IP)
                        if (addr.hostAddress == "192.168.49.1") return addr.hostAddress

                        // Fallback: search for any interface with "p2p" in name
                        if (ifaceName == null && iface.name.lowercase().contains("p2p")) return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Error getting local IP", e)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun makeVisible() {
        registerReceiverIfNeeded()
        val mgr = manager ?: return
        val ch = channel ?: return

        // Ensure WiFi is enabled (Required for P2P)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            AppLog.w("WifiDirectManager: WiFi is disabled. Cannot start P2P discovery.")
            ToastUtils.showToast(context, context.getString(R.string.wifi_disabled_info), Toast.LENGTH_LONG)
            isGroupCreatingOrCreated = false
            return
        }

        isGroupCreatingOrCreated = true

        // Reflection Hack to set name
        try {
            val method = mgr.javaClass.getMethod("setDeviceName", WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java)
            method.invoke(mgr, ch, "OpenHU", object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.i("WifiDirectManager: Name set to OpenHU") }
                override fun onFailure(reason: Int) {}
            })
        } catch (e: Exception) {}

        // 1. Stop any ongoing discovery
        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { checkGroupAndCreate() }
            override fun onFailure(reason: Int) { checkGroupAndCreate() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun checkGroupAndCreate() {
        if (checkGroupAndCreateInFlight) {
            AppLog.d("WifiDirectManager: checkGroupAndCreate already in flight, skipping duplicate call")
            return
        }
        checkGroupAndCreateInFlight = true
        markP2pRequest()
        // Keyed: an un-keyed timer from an earlier run fires 8s later and clears a guard this run
        // is relying on, which lets two teardown/create pairs run at once - the very race the flag
        // exists to prevent.
        val guardToken = ++checkGroupAndCreateToken
        val gen = generation
        handler.postDelayed({
            if (guardToken == checkGroupAndCreateToken) checkGroupAndCreateInFlight = false
        }, 8000L)

        isGroupOwner = false
        isConnected = false

        manager?.requestGroupInfo(channel) { group ->
            if (group == null) {
                AppLog.i("No existing P2P group, creating new one")
                checkGroupAndCreateInFlight = false
                if (supersededByStop(gen, "group creation")) return@requestGroupInfo
                createNewGroup(0)
                return@requestGroupInfo
            }

            // Reusing an existing group desyncs the peer's WPS/PBC registrar into a tight
            // PROV-DISC retry storm (confirmed via live-device bisection against `ebab63a8`,
            // whose only change was skipping this teardown on reuse) — tear down and recreate
            // on every reuse rather than trying to detect which ones are broken.
            // NOTE: this used to also call deletePersistentGroup() to purge the profile (so a
            // plain createGroup() wouldn't reuse the same SSID/netId) — confirmed on-device that
            // call is rejected outright for every netId, including the profile's own real one.
            // Dropped; likely a permission this app doesn't hold.
            AppLog.i("WifiDirectManager: Existing P2P group found — removing and recreating fresh for a clean WPS/PBC registrar")
            // The next createGroup() call generates a brand-new GO interface with a new random
            // MAC — a cached BSSID from the group we're tearing down is now stale and must never
            // be delivered for the new one.
            lastKnownBssid = null
            lastKnownBssidIface = null
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    checkGroupAndCreateInFlight = false
                    if (supersededByStop(gen, "group recreate")) return
                    createNewGroup(0)
                }
                override fun onFailure(reason: Int) {
                    AppLog.w("WifiDirectManager: removeGroup before recreate failed: ${getP2pErrorString(reason)}")
                    checkGroupAndCreateInFlight = false
                    if (supersededByStop(gen, "group recreate")) return
                    createNewGroup(0)
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup(retryCount: Int) {
        val mgr = manager ?: return
        val ch = channel ?: return

        if (isConnected || isGroupOwner) {
            AppLog.d("WifiDirectManager: Group already active/created (isConnected=$isConnected, isGroupOwner=$isGroupOwner). Skipping createGroup retry.")
            return
        }

        lastKnownBssid = null
        lastKnownBssidIface = null

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: P2P Group created (fresh this session).")
                isGroupOwner = true
                tightBurstCount = 0
                lastConnChangedElapsedMs = 0L
                startDiscoveryLoop()
            }
            override fun onFailure(reason: Int) {
                if (reason == 2 && retryCount < 3) { // 2 = BUSY
                    AppLog.w("WifiDirectManager: Chip is BUSY, retrying in 2s...")
                    handler.postDelayed({ createNewGroup(retryCount + 1) }, 2000L)
                } else {
                    AppLog.e("WifiDirectManager: createGroup failed: $reason")
                    isGroupCreatingOrCreated = false
                }
            }
        })
    }

    private fun startDiscoveryLoop() {
        handler.removeCallbacks(discoveryRunnable)
        handler.post(discoveryRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val ch = channel
        if (ch != null) {
            val appSettings = App.provide(context).settings
            if (appSettings.wifiConnectionMode == WifiLauncherMode.HELPER && appSettings.helperConnectionStrategy == HelperStrategy.WIFI_DIRECT) {
                AapService.scanningState.value = true
            }
            manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // INFO, not DEBUG: discoverPeers() puts the P2P radio into find mode, sweeping
                    // the social channels, and on a single-radio unit hosting a group that is
                    // seconds of silence for everything already on it. Two reporters' captures show
                    // exactly that shape on this loop's ten-second cadence, and neither could be
                    // checked against it - the only line the loop left was at a level nobody is
                    // ever asked to capture. A search running under a live session is the anomaly,
                    // so say which case this is.
                    val sessionLive = isNativeSessionConnected?.invoke() == true
                    AppLog.i(
                        "WifiDirectManager: Discovery active - peer search running%s",
                        if (sessionLive) " while an Android Auto session is connected" else ""
                    )
                    if (appSettings.wifiConnectionMode == WifiLauncherMode.HELPER && appSettings.helperConnectionStrategy == HelperStrategy.WIFI_DIRECT) {
                        handler.postDelayed({
                            if (!isClientConnected) {
                                AapService.scanningState.value = false
                            }
                        }, 2500L)
                    }
                }
                override fun onFailure(reason: Int) {
                    AppLog.w("WifiDirectManager: Discovery failed: $reason")
                    AapService.scanningState.value = false
                }
            })
        }
    }

    /**
     * Boomerang Hack: Briefly triggers system WiFi settings to wake up the radio.
     * Currently not used by default but kept in code for future use.
     */
    private fun triggerWifiSettings() {
        try {
            val intent = Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings\$WifiP2pSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {}
        }

        handler.postDelayed({
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                context.startActivity(intent)
            } catch (e: Exception) {}
        }, 800L)
    }

    @SuppressLint("MissingPermission")
    fun startNativeAaQuietHost() {
        registerReceiverIfNeeded()
        isGroupCreatingOrCreated = true
        markP2pRequest()
        var mgr = manager
        var ch = channel

        if (mgr == null || ch == null) {
            AppLog.w("WifiDirectManager: manager ($mgr) or channel ($ch) is null. Attempting re-init...")
            try {
                val newManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                val newChannel = newManager?.initialize(context, context.mainLooper, null)
                if (newManager != null && newChannel != null) {
                    manager = newManager
                    channel = newChannel
                    mgr = newManager
                    ch = newChannel
                    AppLog.i("WifiDirectManager: Re-init successful and fields updated.")
                    registerReceiverIfNeeded()
                } else {
                    AppLog.e("WifiDirectManager: Re-init failed. Cannot start Quiet Host.")
                    isGroupCreatingOrCreated = false
                    return
                }
            } catch (e: Exception) {
                AppLog.e("WifiDirectManager: Exception during re-init", e)
                isGroupCreatingOrCreated = false
                return
            }
        }

        // Ensure WiFi is enabled (Required for P2P)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            AppLog.i("WifiDirectManager: WiFi is disabled but needed for Native AA. Attempting to enable...")
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
            } else {
                showToast("Native AA requires Wi-Fi. Please turn it on.")
                // We return for now, the user must turn it on. In the future we could open settings.
                isGroupCreatingOrCreated = false
                return
            }
            // Wait a bit for WiFi to wake up
            handler.postDelayed({
                if (isGroupCreatingOrCreated) {
                    isGroupCreatingOrCreated = false
                    startNativeAaQuietHost()
                }
            }, 2000L)
            return
        }

        AppLog.i("WifiDirectManager: startNativeAaQuietHost() requested. Removing old group if any...")
        nativeGroupCreationMode = NATIVE_GROUP_MODE_UNKNOWN
        lastNativeGroupStatusMessage = null
        native5GhzBandMismatchRetries = 0
        nativeRequestedBand = NativeGroupBandPolicy.Band.UNSPECIFIED
        nativeRequestedFrequency = 0
        // legacyChannelAttempt is deliberately not reset here. The handshake calls
        // triggerWifiDirectRefresh() every ten seconds while it waits for credentials, and each one
        // lands back in this method - so resetting made rung 1 the only rung the ladder ever tried.
        // stop() clears it, which ties the ladder to the mode rather than to a refresh.
        lastUnfriendlyChannelSsid = null
        lastCoexistenceKey = null
        lastFrequencyUnreadableSsid = null
        nativeJoinWatchdogSsid = null
        nativeRecreateCount = 0
        cancelNativeJoinWatchdog()
        recreateNativeGroup(forceStandard = false)
    }

    private fun delayedCreateQuietGroup(retryCount: Int) {
        handler.postDelayed({ createQuietGroup(retryCount) }, 500L)
    }

    @SuppressLint("MissingPermission")
    private fun createQuietGroup(retryCount: Int) {
        val mgr = manager ?: return
        val ch = channel ?: return

        // Read once per attempt rather than held in a field: the setting is written between runs on
        // a rig, and a group made after the write must be the one the write asked for.
        val appSettings = App.provide(context).settings
        val preference = P2pBandPreference.fromSetting(appSettings.wifiDirectBand)
        // Read per attempt like the setting above, and for the same reason: this is cheap, and a
        // value cached at construction would outlive a WiFi service that came up later.
        val supports5Ghz = WifiBandCapability.supports5Ghz(context)
        val band = NativeGroupBandPolicy.bandFor(preference, supports5Ghz)
        val bandLabel = NativeGroupBandPolicy.label(band)
        val chosenChannel = appSettings.fiveGhzChannel
        // Asking for the 5 GHz *band* is answered by picking a random one of eight frequencies,
        // four of them in UNII-3, which several regulatory domains forbid phones from joining - so
        // on those phones half of all bring-ups are invisible. A pinned channel replaces the band
        // request with a frequency, which the platform treats as forced. 0 means ask for the band.
        val requestedFrequency = if (pinnedChannelAbandoned) 0
            else NativeGroupBandPolicy.requestedFrequencyMhz(band, chosenChannel)

        AppLog.i("WifiDirectManager: Attempting createGroup for Native AA (Attempt $retryCount)...")
        // Said on every bring-up, including the default one: a line that only appears in the unusual
        // case is a line whose absence tells a reader nothing. Same for the radio's own answer,
        // which two open issues spent weeks guessing at.
        AppLog.i("WifiDirectManager: ${WifiBandCapability.describe(supports5Ghz)}.")
        AppLog.i("WifiDirectManager: Band preference is ${NativeGroupBandPolicy.describePreference(preference, supports5Ghz)}.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Builder.build() requires networkName+passphrase (or a peer address) or it
                // throws IllegalStateException — onGroupInfoAvailable() reads the real values
                // back afterwards, same as the standard-fallback path.
                val builder = WifiP2pConfig.Builder()
                    .setNetworkName(generateP2pNetworkName())
                    .setPassphrase(generateP2pPassphrase())
                // Exactly one of the two: build() throws IllegalStateException if both are set.
                if (requestedFrequency > 0) {
                    builder.setGroupOperatingFrequency(requestedFrequency)
                } else {
                    builder.setGroupOperatingBand(
                        if (band == NativeGroupBandPolicy.Band.GHZ_2_4) WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                        else WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                    )
                }
                val config = builder.build()

                // Recorded here and not before the gate: this is the only branch that asks for a
                // band, so it is the only one whose answer can be mismatched. Below Q the request
                // does not exist and standardCreateGroup leaves the field UNSPECIFIED.
                nativeRequestedBand = band
                nativeRequestedFrequency = requestedFrequency
                AppLog.i("WifiDirectManager: Requesting Native AA P2P group on $bandLabel band.${if (preference != P2pBandPreference.AUTO) " Chosen by the user." else ""}")
                AppLog.i(
                    "WifiDirectManager: 5 GHz channel is ${FiveGhzChannelPolicy.describe(chosenChannel)}" +
                        if (requestedFrequency > 0) ", asked for as a fixed $requestedFrequency MHz."
                        else if (pinnedChannelAbandoned) ", already refused by this unit, so the band decides."
                        else ", so the driver picks within the band."
                )
                markP2pRequest()
                mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        AppLog.i("WifiDirectManager: $bandLabel createGroup SUCCESS!")
                        nativeGroupCreationMode =
                            if (band == NativeGroupBandPolicy.Band.GHZ_2_4) NATIVE_GROUP_MODE_24GHZ_REQUESTED
                            else NATIVE_GROUP_MODE_5GHZ_REQUESTED
                        isGroupOwner = true
                        handler.postDelayed({
                            mgr.requestConnectionInfo(ch, this@WifiDirectManager)
                            mgr.requestGroupInfo(ch, this@WifiDirectManager)
                        }, 1000L)
                    }
                    override fun onFailure(reason: Int) {
                        onQuietGroupFailed(mgr, ch, retryCount, preference, chosenChannel,
                            requestedFrequency, bandLabel, getP2pErrorString(reason), null)
                    }
                })
                return
            } catch (t: Throwable) {
                onQuietGroupFailed(mgr, ch, retryCount, preference, chosenChannel,
                    requestedFrequency, bandLabel, "crashed before any async result", t)
                return
            }
        }

        // Below Q there is no band request, so the driver picks the channel unless it is given a
        // frequency list. This has to happen before createGroup: the platform only accepts a channel
        // change while no group exists, and drops it silently afterwards.
        //
        // A ladder, walked one rung per failed bring-up: 5 GHz, then 2.4 GHz, then no restriction at
        // all. The rungs matter because the request is a disallowed-frequency list, so a unit that
        // cannot host a group owner on the band it names does not land on the other one - it forms
        // no group. standardCreateGroup() is what advances the index when that happens.
        val ladder = WifiP2pOperatingChannelPolicy.attemptChannels(
            sdkInt = Build.VERSION.SDK_INT,
            preference = preference,
            chosenChannel = FiveGhzChannelPolicy.pinnedChannel(chosenChannel),
            supports5Ghz = supports5Ghz,
        )
        val ladderLabel = ladder.joinToString { channel ->
            "$channel (${WifiP2pOperatingChannelPolicy.frequencyMhzFor(channel)} MHz)"
        }
        val operatingChannel = ladder.getOrNull(legacyChannelAttempt)
            ?: WifiP2pOperatingChannelPolicy.CHANNEL_UNRESTRICTED
        if (operatingChannel == WifiP2pOperatingChannelPolicy.CHANNEL_UNRESTRICTED) {
            // Every rung spent. Also where an empty ladder lands, which only an Android 10+ device
            // produces and which the branch above already returned for - kept as the same path
            // rather than a special case, because both mean "ask for nothing".
            AppLog.w(
                "WifiDirectManager: every operating channel this unit was offered " +
                    "(${ladderLabel.ifEmpty { "none" }}) has been tried, so the band goes back to " +
                    "being the driver's choice."
            )
            standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_LEGACY)
            return
        }

        val frequency = WifiP2pOperatingChannelPolicy.frequencyMhzFor(operatingChannel)
        if (legacyChannelRequestUnanswered) {
            AppLog.i(
                "WifiDirectManager: not asking for operating channel $operatingChannel " +
                    "($frequency MHz) again - this unit left the last request unanswered, so the " +
                    "band stays the driver's choice."
            )
            standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_LEGACY)
            return
        }
        AppLog.i(
            "WifiDirectManager: no band request below Android 10, so asking for operating channel " +
                "$operatingChannel ($frequency MHz) instead - rung ${legacyChannelAttempt + 1} of " +
                "${ladder.size} ($ladderLabel). The group cannot be told which band to use here; " +
                "this restricts which frequencies it may pick."
        )
        markP2pRequest()
        WifiP2pChannelCompat.setOperatingChannel(mgr, ch, operatingChannel, handler) { applied, answered, detail ->
            legacyChannelRestrictionApplied = applied
            if (applied) {
                AppLog.i("WifiDirectManager: operating channel $operatingChannel ($frequency MHz) $detail.")
            } else if (answered) {
                AppLog.w(
                    "WifiDirectManager: this unit would not take an operating channel ($detail), so " +
                        "the group's band stays the driver's choice, as it was before."
                )
            } else {
                legacyChannelRequestUnanswered = true
                AppLog.w(
                    "WifiDirectManager: this unit never answered the operating channel request " +
                        "($detail), so the group's band stays the driver's choice and the rest of " +
                        "this session goes straight to creating the group."
                )
            }
            standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_LEGACY)
        }
    }

    /**
     * What a failed Native AA group request does next.
     *
     * The ordering is [NativeGroupBandPolicy.nextStepAfterFailure]'s; this only carries it out and
     * says so in the log. A crash before the async result lands here too, because a request the
     * platform rejects outright and one it accepts and then fails need the same ladder.
     */
    private fun onQuietGroupFailed(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        retryCount: Int,
        preference: P2pBandPreference,
        chosenChannel: Int,
        requestedFrequency: Int,
        bandLabel: String,
        reasonStr: String,
        crash: Throwable?,
    ) {
        val requestLabel =
            if (requestedFrequency > 0) "$bandLabel ${FiveGhzChannelPolicy.describe(chosenChannel)}"
            else bandLabel
        when (NativeGroupBandPolicy.nextStepAfterFailure(
            preference = preference,
            channelPinned = requestedFrequency > 0,
            retriesSoFar = retryCount,
            maxRetries = MAX_NATIVE_5GHZ_CREATE_RETRIES,
        )) {
            NativeGroupBandPolicy.NextStep.RETRY -> {
                val message = "WifiDirectManager: $requestLabel createGroup failed ($reasonStr), " +
                    "removing group and retrying in 2s " +
                    "(retry ${retryCount + 1}/$MAX_NATIVE_5GHZ_CREATE_RETRIES)..."
                if (crash != null) AppLog.e(message, crash) else AppLog.w(message)
                retryQuietGroup(mgr, ch, retryCount + 1, removeFirst = crash == null)
            }

            NativeGroupBandPolicy.NextStep.DROP_PINNED_CHANNEL -> {
                pinnedChannelAbandoned = true
                AppLog.w(
                    "WifiDirectManager: $requestLabel createGroup retries exhausted ($reasonStr). This unit " +
                        "will not host a group on that channel, so the request goes back to the $bandLabel band " +
                        "and the driver picks the channel. If the phone still cannot see the head unit, that " +
                        "is why - the channel you chose is not the one it is on."
                )
                retryQuietGroup(mgr, ch, 0, removeFirst = crash == null)
            }

            NativeGroupBandPolicy.NextStep.STANDARD_FALLBACK -> {
                AppLog.w("WifiDirectManager: $requestLabel createGroup retries exhausted ($reasonStr). Falling back to standard createGroup.")
                standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_FALLBACK)
            }

            NativeGroupBandPolicy.NextStep.GIVE_UP -> {
                // "5 GHz only" means it. A group on 2.4 GHz can connect, look entirely healthy and
                // show nothing, which is harder to diagnose than no group, and the user asked not
                // to be given one.
                AppLog.e(
                    "WifiDirectManager: $requestLabel createGroup retries exhausted ($reasonStr) and the " +
                        "band is set to 5 GHz only, so no group is created. Set the WiFi Direct band " +
                        "to Auto if this unit cannot host one."
                )
                isGroupCreatingOrCreated = false
            }
        }
    }

    /**
     * Ask again in 2 s, clearing any group the platform made first.
     *
     * Not after a crash: the request never reached the framework, so there is nothing to remove and
     * asking would spend a bring-up on the rate instrument for nothing.
     */
    private fun retryQuietGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        nextRetry: Int,
        removeFirst: Boolean,
    ) {
        if (!removeFirst) {
            handler.postDelayed({ createQuietGroup(nextRetry) }, 2000L)
            return
        }
        markP2pRequest()
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { handler.postDelayed({ createQuietGroup(nextRetry) }, 2000L) }
            override fun onFailure(r: Int) { handler.postDelayed({ createQuietGroup(nextRetry) }, 2000L) }
        })
    }

    private fun generateP2pNetworkName(): String {
        val suffix = AapService.wifiDirectName.value
            ?.filter { it.isLetterOrDigit() }
            ?.take(20)
            ?.ifEmpty { null } ?: "HeadUnit"
        val code = (('A'..'Z') + ('0'..'9')).let { pool -> "${pool.random()}${pool.random()}" }
        return "DIRECT-$code-$suffix"
    }

    private fun generateP2pPassphrase(): String {
        val pool = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..12).map { pool.random() }.joinToString("")
    }

    private fun getP2pErrorString(reason: Int): String {
        return when(reason) {
            0 -> "ERROR (Internal Error)"
            1 -> "P2P_UNSUPPORTED"
            2 -> "BUSY (System is busy, retry needed)"
            else -> "UNKNOWN ($reason)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun standardCreateGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, retryCount: Int, groupMode: String) {
        markP2pRequest()
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: Standard createGroup SUCCESS!")
                // The one thing that disproves the record: this unit just hosted a group.
                ConnectionIssues.clear(context, ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED)
                nativeGroupCreationMode = groupMode
                // The platform chose the band here, so there is no mismatch to correct, and no
                // frequency was named either.
                nativeRequestedBand = NativeGroupBandPolicy.Band.UNSPECIFIED
                nativeRequestedFrequency = 0
                isGroupOwner = true
                // The restriction is a whitelist of one frequency and it applies to the whole P2P
                // interface, not just to group creation - while it stands, the 2.4 GHz social
                // channels are banned and discovery cannot run. The group keeps the channel it was
                // formed on, so the restriction has done its work and must come off now.
                releaseLegacyChannelRestriction(mgr, ch)
                handler.postDelayed({
                    mgr.requestConnectionInfo(ch, this@WifiDirectManager)
                    mgr.requestGroupInfo(ch, this@WifiDirectManager)
                }, 1000L)
            }
            override fun onFailure(reason: Int) {
                val reasonStr = getP2pErrorString(reason)
                if (reason == 2 && retryCount < MAX_NATIVE_STANDARD_CREATE_RETRIES) {
                    AppLog.w("WifiDirectManager: standard createGroup failed ($reasonStr), removing group and retrying standard in 2s...")
                    markP2pRequest()
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { handler.postDelayed({ standardCreateGroup(mgr, ch, retryCount + 1, groupMode) }, 2000L) }
                        override fun onFailure(r: Int) { handler.postDelayed({ standardCreateGroup(mgr, ch, retryCount + 1, groupMode) }, 2000L) }
                    })
                } else if (legacyChannelRestrictionApplied) {
                    // The restriction is a frequency list, so a unit whose P2P firmware cannot host a
                    // group owner on that band has nowhere legal to put one and fails outright rather
                    // than landing on the other. Step down the ladder rather than straight to no
                    // restriction: naming 2.4 GHz gives the unit somewhere to go, and clearing is
                    // what happens once every rung has been offered.
                    legacyChannelAttempt++
                    AppLog.w(
                        "WifiDirectManager: no group formed while an operating channel was requested " +
                            "($reasonStr) - this unit cannot host a group owner on that band. Trying " +
                            "the next channel it was offered."
                    )
                    markP2pRequest()
                    WifiP2pChannelCompat.clearOperatingChannel(mgr, ch, handler) { _, _, detail ->
                        legacyChannelRestrictionApplied = false
                        AppLog.i("WifiDirectManager: operating channel restriction cleared ($detail).")
                        createQuietGroup(0)
                    }
                } else {
                    reportGroupRefusal(reasonStr)
                    isGroupCreatingOrCreated = false
                }
            }
        })
    }

    /**
     * Gives the frequency list back once the group exists.
     *
     * Not tidiness: the restriction bans every frequency except the one it names, including the
     * 2.4 GHz social channels discovery runs on, and it is state in the supplicant that outlives
     * this app. A group that has already formed keeps its channel, so nothing is lost by clearing.
     */
    private fun releaseLegacyChannelRestriction(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (!legacyChannelRestrictionApplied) return
        legacyChannelRestrictionApplied = false
        WifiP2pChannelCompat.clearOperatingChannel(mgr, ch, handler) { applied, _, detail ->
            if (applied) {
                AppLog.i("WifiDirectManager: operating channel restriction released; discovery can use the social channels again.")
            } else {
                AppLog.w(
                    "WifiDirectManager: could not release the operating channel restriction ($detail). " +
                        "Peer discovery may stay crippled until WiFi is restarted."
                )
            }
        }
    }

    private fun isNativeAaMode(): Boolean {
        return App.provide(context).settings.wifiConnectionMode == WifiLauncherMode.NATIVE
    }

    private fun shouldRetryNativeGroupFor5Ghz(frequency: Int): Boolean =
        NativeGroupBandPolicy.shouldRetryFor5Ghz(
            requested = nativeRequestedBand,
            frequencyMhz = frequency,
            retriesSoFar = native5GhzBandMismatchRetries,
            maxRetries = MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES,
        )

    @SuppressLint("MissingPermission")
    private fun removeGroupAndRetryNative5Ghz() {
        val mgr = manager ?: return
        val ch = channel ?: return
        // The next createGroup() call generates a brand-new GO interface with a new random
        // MAC — a cached BSSID from the group we're tearing down is now stale and must never
        // be delivered for the new one.
        lastKnownBssid = null
        lastKnownBssidIface = null
        // The group this replaces is gone; any credential delivery still waiting on it describes a
        // network that will not exist by the time it lands.
        credentialsEpoch++
        val gen = generation
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (supersededByStop(gen, "5GHz band-mismatch retry")) return
                delayedCreateQuietGroup(0)
            }
            override fun onFailure(reason: Int) {
                AppLog.w("WifiDirectManager: removeGroup before 5GHz band-mismatch retry failed: ${getP2pErrorString(reason)}")
                if (supersededByStop(gen, "5GHz band-mismatch retry")) return
                delayedCreateQuietGroup(0)
            }
        })
    }

    /**
     * Tear down any current P2P group and create a fresh native quiet-host group.
     * [forceStandard] skips the band request altogether, for phones that can't join a 5GHz group
     * owner. It asks for nothing rather than asking for 2.4 GHz, so the platform decides.
     *
     * Used to also call deletePersistentGroup() here, as the Helper mode path does, but on-device
     * that is rejected for every netId — likely a permission this app lacks. Dropped.
     */
    @SuppressLint("MissingPermission")
    private fun recreateNativeGroup(forceStandard: Boolean) {
        val mgr = manager ?: return
        val ch = channel ?: return
        lastKnownBssid = null
        lastKnownBssidIface = null
        // Let an in-progress credential wait pick up the fresh group's creds, not stale ones.
        credentialsEpoch++
        onNativeGroupInvalidated?.invoke()
        val gen = generation
        val createFresh = {
            // The removeGroup below is the teardown a user exit relies on. If stop() ran while it was
            // in flight, recreating here puts a group back up that nothing is managing and that the
            // phone will keep trying to join - the opposite of what the exit asked for.
            if (supersededByStop(gen, "Native AA group recreate")) Unit
            else if (forceStandard) standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_FALLBACK)
            else delayedCreateQuietGroup(0)
        }
        markP2pRequest()
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createFresh() }
            override fun onFailure(reason: Int) {
                AppLog.d("WifiDirectManager: Native AA removeGroup before recreate failed (reason=${getP2pErrorString(reason)}); expected if no group existed")
                createFresh()
            }
        })
    }

    /** Recover a native quiet-host group when the phone never joins: recreate a fresh one, and
     *  after a couple of tries drop the band request entirely and let the platform choose. Bounded
     *  by [MAX_NATIVE_JOIN_RECREATES]. */
    private fun recoverNativeGroup(reason: String) {
        cancelNativeJoinWatchdog()
        if (isClientConnected) return
        if (isNativeSessionConnected?.invoke() == true) {
            AppLog.i("WifiDirectManager: recoverNativeGroup() called but a session is already connected — not tearing down a working connection.")
            nativeRecreateCount = 0
            return
        }
        if (nativeRecreateCount >= MAX_NATIVE_JOIN_RECREATES) {
            AppLog.w("WifiDirectManager: Native AA — phone still not connected after $nativeRecreateCount recreations ($reason); giving up until the next start.")
            return
        }
        nativeRecreateCount++
        val forceStandard = nativeRecreateCount >= NATIVE_FORCE_STANDARD_AFTER
        // Not "forcing 2.4GHz", which this line claimed for as long as it has existed: forceStandard
        // calls the no-band createGroup, so what actually happens is that the platform chooses.
        AppLog.w("WifiDirectManager: Native AA recovery ($reason): recreate attempt $nativeRecreateCount/$MAX_NATIVE_JOIN_RECREATES${if (forceStandard) ", dropping the band request and letting this unit choose" else ""}.")
        recreateNativeGroup(forceStandard)
    }

    /** (Re)arm the native join watchdog; no-op unless we are a native-mode group owner with no
     *  client yet. */
    /**
     * Arms the join watchdog, at most once per group.
     *
     * [groupSsid] identifies the group this is being armed for. `onGroupInfoAvailable` fires three or
     * four times per group and once more for every CONNECTION_CHANGED, and re-arming pushed the
     * deadline out by the full timeout each time - so a phone retrying its join at any cadence faster
     * than the timeout starved the watchdog indefinitely and the bounded recovery it exists to run
     * never ran. Pass null where the re-arm is a real state change (a client leaving, WiFi coming
     * back), which resets the identity and lets the next group arm afresh.
     */
    private fun armNativeJoinWatchdog(groupSsid: String? = null) {
        if (groupSsid != null && groupSsid == nativeJoinWatchdogSsid) return
        nativeJoinWatchdogSsid = groupSsid
        handler.removeCallbacks(nativeJoinWatchdog)
        if (isNativeAaMode() && isGroupOwner && !isClientConnected && isNativeSessionConnected?.invoke() != true) {
            handler.postDelayed(nativeJoinWatchdog, NATIVE_JOIN_TIMEOUT_MS)
        }
    }

    private fun cancelNativeJoinWatchdog() {
        handler.removeCallbacks(nativeJoinWatchdog)
    }

    private fun notifyNativeGroupStarted(ssid: String, frequency: Int, band: String) {
        val frequencyText = if (frequency > 0) "$frequency MHz" else "frequency unknown"
        val message = "Native AA WiFi Direct: $band ($frequencyText), $nativeGroupCreationMode"
        if (message == lastNativeGroupStatusMessage) return

        lastNativeGroupStatusMessage = message
        AppLog.i("WifiDirectManager: $message, SSID=$ssid")
        showToast(message)
    }

    private fun showToast(message: String) {
        handler.post {
            ToastUtils.showToast(context, message, Toast.LENGTH_LONG)
        }
    }

    private val macRegex = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
    private val maskedMacs = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /**
     * The group owner's BSSID as the framework knows it. `getGroupOwnerBssid()` is not in the
     * public SDK but some vendor trees carry it, and where it exists it is a direct answer rather
     * than a derivation, so it is asked first.
     */
    private fun getGroupOwnerBssid(group: WifiP2pGroup): String? = try {
        group.javaClass.getMethod("getGroupOwnerBssid").invoke(group)?.toString()
    } catch (e: Throwable) {
        null
    }

    /**
     * Every BSSID source and what each answered, printed once per group.
     *
     * With nine of them in the chain, "all fallbacks failed" tells a reporter's log nothing about
     * which hardware refused what. This is the block to ask for, and it turns a second test build
     * into a paste.
     */
    private fun logBssidSourceDump(
        group: WifiP2pGroup,
        iface: String?,
        ssid: String?,
        staticOverride: String?
    ) {
        if (ssid != null && ssid == lastBssidDumpSsid) return
        lastBssidDumpSsid = ssid
        fun report(label: String, value: String?) =
            AppLog.i("WifiDirectManager:   ${label.padEnd(32)} = ${value ?: "null"}")
        AppLog.i("WifiDirectManager: == BSSID source dump (iface=${iface ?: "unknown"}) ==")
        report("static override (Settings)", staticOverride)
        report("getGroupOwnerBssid()", getGroupOwnerBssid(group))
        report("IPv6 link-local ($iface)", InterfaceMacReader.fromIpv6LinkLocal(iface))
        report("IPv6 link-local (any p2p/ap)", InterfaceMacReader.fromIpv6LinkLocal(null))
        report("NetworkInterface.hardwareAddress", getWifiDirectMac(iface))
        report("lastKnownBssid ($lastKnownBssidIface)", lastKnownBssid)
        report("requestDeviceInfo", localDeviceAddress)
        report("group.owner.deviceAddress", group.owner?.deviceAddress)
        report("sysfs / ip link", getMacFromShell(iface))
        report("Settings.Secure p2p address", try {
            Settings.Secure.getString(context.contentResolver, "wifi_p2p_device_address")
        } catch (e: Exception) {
            "err: ${e.message}"
        })
        report("reflection over WifiP2pGroup", getMacFromReflection(group))
        AppLog.i("WifiDirectManager: == end BSSID source dump ==")
    }

    /**
     * Last-resort BSSID lookup: reflect over every declared field (including inherited ones) of
     * the WifiP2pGroup and its owner WifiP2pDevice, looking for any String that looks like a MAC
     * and isn't one of the known privacy-masked placeholders. Some OEM builds mask the public
     * NetworkInterface/deviceAddress getters but leave other internal fields populated with the
     * real value.
     */
    private fun getMacFromReflection(group: WifiP2pGroup): String? {
        val candidates = listOfNotNull(group, group.owner)
        for (obj in candidates) {
            var klass: Class<*>? = obj.javaClass
            while (klass != null) {
                for (field in klass.declaredFields) {
                    try {
                        field.isAccessible = true
                        val value = field.get(obj) as? String ?: continue
                        if (macRegex.matches(value) && value !in maskedMacs) {
                            AppLog.d("WifiDirectManager: getMacFromReflection found candidate in ${klass.simpleName}.${field.name}: $value")
                            return value
                        }
                    } catch (e: Exception) {
                        // Ignore inaccessible/incompatible fields and keep scanning.
                    }
                }
                klass = klass.superclass
            }
        }
        return null
    }

    private fun getMacFromShell(iface: String?): String? {
        // Fallback: If iface is null, try to find a p2p interface name
        val targetIface = iface ?: getInterfaceByIp("192.168.49.1") ?: discoveredInterface

        if (targetIface != null) {
            // Try reading directly from sysfs
            try {
                val file = File("/sys/class/net/$targetIface/address")
                if (file.exists()) {
                    val mac = file.readText().trim().lowercase()
                    if (mac.isNotEmpty() && mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") {
                        AppLog.i("WifiDirectManager: MAC retrieved via sysfs ($targetIface): $mac")
                        return mac
                    }
                }
            } catch (e: Exception) {}

            // Try ip link
            try {
                val process = Runtime.getRuntime().exec("ip link show $targetIface")
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val match = Regex("link/ether (([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})").find(line ?: "")
                    if (match != null) {
                        val mac = match.groupValues[1].lowercase()
                        if (mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") return mac
                    }
                }
            } catch (e: Exception) {}
        }

        // LAST RESORT: Scan ALL interfaces in sysfs for anything that looks like P2P
        AppLog.i("WifiDirectManager: getMacFromShell: Target failed, scanning ALL interfaces in sysfs...")
        try {
            val netDir = File("/sys/class/net")
            val interfaces = netDir.listFiles()
            if (interfaces != null) {
                for (dir in interfaces) {
                    val name = dir.name.lowercase()
                    if (name.contains("p2p") || name.contains("wlan") || name.contains("ap")) {
                        val addrFile = File(dir, "address")
                        if (addrFile.exists()) {
                            val mac = addrFile.readText().trim().lowercase()
                            if (mac.isNotEmpty() && mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") {
                                AppLog.i("WifiDirectManager: Last resort MAC found on ${dir.name}: $mac")
                                return mac
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        return null
    }

    fun stop() {
        AppLog.i("WifiDirectManager: Stopping and cleaning up...")
        generation++
        credentialsEpoch++
        isGroupCreatingOrCreated = false
        handler.removeCallbacksAndMessages(null)
        // Both of these guard an operation that is finished the moment we stop, and both used to be
        // cleared only by a posted runnable that the line above just cancelled - so a stop() landing
        // inside either window latched it true for the life of the process. A flag set in only one
        // direction is how a long-lived manager ends up unable to re-arm.
        checkGroupAndCreateInFlight = false
        isClientConnected = false
        nativeGroupCreationMode = NATIVE_GROUP_MODE_UNKNOWN
        native5GhzBandMismatchRetries = 0
        lastUnfriendlyChannelSsid = null
        lastCoexistenceKey = null
        lastFrequencyUnreadableSsid = null
        nativeJoinWatchdogSsid = null
        nativeRecreateCount = 0
        lastNativeGroupStatusMessage = null
        legacyChannelAttempt = 0
        legacyChannelRequestUnanswered = false
        lastGroupRefusalReportAtMs = 0L
        // Unconditional, and not gated on legacyChannelRestrictionApplied: a request that never
        // answered may still have been applied, and the restriction is supplicant state that
        // outlives this process.
        pinnedChannelAbandoned = false
        manager?.let { mgr -> channel?.let { ch -> releaseLegacyChannelRestriction(mgr, ch) } }
        AapService.scanningState.value = false
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        // Follows the receiver rather than outliving it: registerReceiverIfNeeded() is a no-op while
        // this is true, so leaving it set means a restarted manager never hears CONNECTION_CHANGED
        // again - no client-connected state, and a join watchdog that fires on a group the phone has
        // successfully joined.
        isReceiverRegistered = false

        if (isGroupOwner) {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.d("WifiDirectManager: Final group removal success") }
                override fun onFailure(reason: Int) { AppLog.d("WifiDirectManager: Final group removal failed: $reason") }
            })
        }

        isGroupOwner = false
        isConnected = false
    }
}
