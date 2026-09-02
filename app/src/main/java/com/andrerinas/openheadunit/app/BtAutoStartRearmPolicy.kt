package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode

/**
 * What a Bluetooth auto-start does to the connection stack. Native AA is the only mode that has to
 * be rebuilt, because a completed handoff closes its listeners for good; the others keep listening
 * while armed, so there the most to do is arm a launcher that nothing has armed.
 */
data class BtAutoStartActions(
    val clearUserExit: Boolean,
    val forceRearmWireless: Boolean,
    val armWirelessIfIdle: Boolean
) {
    val doesNothing: Boolean
        get() = !clearUserExit && !forceRearmWireless && !armWirelessIfIdle

    companion object {
        val NONE = BtAutoStartActions(clearUserExit = false, forceRearmWireless = false, armWirelessIfIdle = false)
    }
}

/**
 * Decides what a Bluetooth auto-start does, per wireless mode and transport. The mode comes from the
 * stored setting, never the launcher, which a Native user exit nulls; everything else is asked of the
 * launcher and is nullable. Native is forced only when it cannot accept at all, so [groupUp] and
 * [networkComingUp] both veto. USB is excluded: it has its own attach and detach triggers.
 */
object BtAutoStartRearmPolicy {

    fun actionsFor(
        mode: WifiLauncherMode,
        wirelessSelected: Boolean,
        sessionUp: Boolean,
        wirelessArmed: Boolean,
        handshakeActive: Boolean?,
        attemptInFlight: Boolean?,
        groupUp: Boolean?,
        networkComingUp: Boolean?
    ): BtAutoStartActions {
        // A network that has been asked for and has not answered is work in progress, exactly like
        // an attempt in flight: everything below would read it as "cannot accept" and rebuild.
        if (sessionUp || attemptInFlight == true || networkComingUp == true) return BtAutoStartActions.NONE
        // An active handshake suppresses everything only while its group is still up; a handshake
        // stranded with no network is a state to rebuild out of, not one to protect.
        if (handshakeActive == true && groupUp != false) return BtAutoStartActions.NONE

        val forceRearm = mode == WifiLauncherMode.NATIVE
        return BtAutoStartActions(
            clearUserExit = true,
            forceRearmWireless = forceRearm,
            armWirelessIfIdle = !forceRearm && wirelessSelected && !wirelessArmed
        )
    }

    /**
     * The Self Mode half of an auto-start. It runs in MainActivity rather than the service, which
     * owns neither the VPN consent dialog nor a foreground window for the projection activity.
     */
    fun launchesSelfMode(selfSelected: Boolean, sessionUp: Boolean): Boolean =
        selfSelected && !sessionUp
}
