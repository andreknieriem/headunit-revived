package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.UsbSessionQuiescePolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

open class WifiLauncherManager(val service: AapService) {

    val sharedServices: WifiLauncherSharedServices = WifiLauncherSharedServices(service)
    var active: WifiLauncher? = null
        private set


    val isActive: Boolean get() = active != null

    val activeMode: WifiLauncherMode? get() = active?.mode

    fun setActiveFromSettings(force: Boolean = false, noInfoToasts: Boolean = true) {
        val settings = App.provide(service).settings

        setActive(settings.wifiConnectionMode, force, noInfoToasts)
    }

    fun setActive(mode: WifiLauncherMode, force: Boolean = false, noInfoToasts: Boolean = true) {
        setActive(mode.factory(this), force, noInfoToasts)
    }

    fun setActive(newLauncher: WifiLauncher, force: Boolean = false, noInfoToasts: Boolean = true) {
        if (newLauncher.manager != this)
            throw IllegalArgumentException("newLauncher.manager is different instance")
        if (active == newLauncher)
            throw IllegalArgumentException("newLauncher is already active")

        if (!force && (active?.hasSameStartConfiguration(newLauncher) ?: false)) {
            AppLog.d("WifiLauncher: WiFi Mode ${newLauncher.mode}.mode with same start-configuration is already initialized.")
            return
        }

        // Every automatic entry point lands here, including the Bluetooth auto-start that fires
        // when the phone comes into range — which on a looping unit would walk straight back into
        // the crash the guard was set to avoid. Explicit user actions release the pause first, so
        // this only ever blocks a start nobody asked for.
        if (Settings.isWirelessPausedByBootLoop(service)) {
            AppLog.w("AapService: Wireless bring-up requested, but it is paused by the boot-loop guard. Open the app to re-enable it.")
            return
        }

        // A wired session is live and we took the wireless stack down for it. Every automatic entry
        // point lands here, including the Bluetooth auto-start a poke can raise on the unit itself,
        // so without this the stack walks straight back up underneath a session that has no use for
        // it. AapService.rearmWirelessAfterWiredSession() is what lets it back in.
        val commManager = App.provide(service).commManager
        if (UsbSessionQuiescePolicy.shouldRefuseBringUp(
                quiescedForThisSession = service.wirelessQuiescedForWiredSession,
                sessionIsLive = commManager.isConnected,
                sessionIsWireless = commManager.isWirelessSession
            )
        ) {
            AppLog.i("AapService: wireless bring-up requested while a USB session is live — not arming it")
            return
        }

        AppLog.i("WifiLauncher: Initializing WiFi Mode: ${newLauncher.mode}")

        // stop old launcher
        active?.stop(WifiLauncherStopSequence.ANY)

        // replace it with new one
        active = newLauncher
        sharedServices.update(newLauncher)
        active?.start(noInfoToasts)
    }

    fun stop(seq: WifiLauncherStopSequence = WifiLauncherStopSequence.ANY) {
        if (active == null)
            return

        active?.stop(seq)

        if (seq.handledAt(WifiLauncherStopSequence.LAST)) {
            sharedServices.stopAll()
            active = null
        }
    }

    /**
     * Starts a sweep on the discovery instance that is already there.
     *
     * Deliberately does not stop the running scan first. stop() is cooperative, so the pair started
     * a second sweep beside the first, and two sweeps probing the head unit server's port at once
     * is how it ends up bound to a connection nobody owns. startScan() is a no-op while a healthy
     * scan is in flight and says so in its return value.
     *
     * @return true if a sweep started, false if one was already in flight, and null if there is no
     *   discovery loop to kick at all. The last two are different situations and only one of them
     *   is worth reacting to, so they are not folded together.
     */
    fun forceStartDiscoveryScan(): Boolean? {
        val discovery = sharedServices.localDiscovery ?: return null

        return discovery.startScan()
    }

    fun startDiscovery(oneShot: Boolean = false) {
        // Allow discovery for Strategy 0 (NSD), 3 (Phone Hotspot) and 4 (Headunit Hotspot)
        if (active == null || active?.hasLocalDiscovery() == false)
            return

        sharedServices.startLocalDiscovery(oneShot)
    }

    fun restartDiscovery() {
        active?.restartDiscovery()
    }
}
