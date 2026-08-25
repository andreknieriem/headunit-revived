package com.andrerinas.openheadunit.aap

/**
 * What to shut down while a session is running over USB rather than over the air.
 *
 * Wireless bring-up is driven entirely by the stored `wifiConnectionMode`, and nothing in it ever
 * asked what the live session is riding on. `initWifiMode()` runs from `onCreate()` before USB is
 * even probed, and `onConnected()` stopped nothing, so a wired session carried the whole wireless
 * stack alongside it. A reporter's USB capture shows all of it at once: a P2P group created ten
 * seconds before the phone was plugged in and torn down only at user exit, RFCOMM listeners open,
 * Nearby discovering, the :5288 server bound, the Native AA join watchdog firing under a live
 * session at 03:01:35, and the Bluetooth poke loop still running 4.1 s after the SSL handshake.
 *
 * None of that can succeed while USB owns the session — no phone can join a group it is not
 * looking for — and some of it is actively harmful. A poke raises an OS-level ACL_CONNECTED, which
 * AutoStartReceiver treats exactly like the user's phone arriving, and the answer to that can be
 * `initWifiMode(force = true)`, which tears down and recreates the P2P group underneath a session
 * that is already up.
 *
 * The rule is deliberately blunt: if the session is not wireless, none of it should be running.
 * Symmetry is the part worth being careful about, so [shouldRearmWireless] exists rather than
 * leaving each caller to remember.
 */
object UsbSessionQuiescePolicy {

    /**
     * Whether to stop the wireless discovery and handshake machinery for this session.
     *
     * Ask the session, never the settings: a USB session can be live while `wifiConnectionMode`
     * names a WiFi route, which is exactly the case this exists for.
     */
    fun shouldQuiesce(sessionIsWireless: Boolean): Boolean = !sessionIsWireless

    /**
     * Whether to drop the P2P group as well.
     *
     * Only when we are the one hosting it. Taking it down is safe here precisely because no phone
     * can be on it: a phone connected over USB is not associated to our group. It is never reused
     * afterwards — [shouldRearmWireless] sends the caller back through `initWifiMode(force = true)`,
     * which creates a fresh one, so the "never reuse a P2P group" rule holds by construction.
     */
    fun shouldStopWifiDirectGroup(sessionIsWireless: Boolean, usesWifiDirect: Boolean): Boolean =
        shouldQuiesce(sessionIsWireless) && usesWifiDirect

    /**
     * A HIGH_PERF WiFi lock keeps the radio out of power save for a link that is not carrying
     * this session's bytes.
     */
    fun shouldAcquireWifiLock(sessionIsWireless: Boolean): Boolean = sessionIsWireless

    /**
     * Whether to refuse a request to arm the wireless stack.
     *
     * The mirror of [shouldQuiesce], for the window between it and [shouldRearmWireless]. Every
     * automatic entry point reaches wireless bring-up - a settings save, the WiFi auto-start
     * receiver, the Bluetooth auto-start that one of our own pokes can raise - and none of them
     * knows a wired session is up. Without this, the stack we just took down comes back underneath
     * it, and on the Native route that means recreating the P2P group and restarting the poke loop
     * beside a session that is already projecting.
     *
     * All three conditions matter: a live wireless session must not be refused, and neither must a
     * bring-up on a unit that never quiesced anything.
     */
    fun shouldRefuseBringUp(
        quiescedForThisSession: Boolean,
        sessionIsLive: Boolean,
        sessionIsWireless: Boolean
    ): Boolean = quiescedForThisSession && sessionIsLive && !sessionIsWireless

    /**
     * Whether a disconnect has to put the wireless stack back.
     *
     * True only if we were the ones who took it down. Unplugging must return the unit to whatever
     * mode it was in, on a user exit as much as on an unexpected drop, so this does not consider
     * why the session ended.
     */
    fun shouldRearmWireless(quiescedForThisSession: Boolean, wirelessModeConfigured: Boolean): Boolean =
        quiescedForThisSession && wirelessModeConfigured
}
