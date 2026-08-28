package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode

/**
 * Whether a Bluetooth auto-start (ACL_CONNECTED from a trusted device) should re-arm the Native
 * AA mode.
 *
 * The mode question is asked of the stored setting, never of the active launcher. A Native user
 * exit stops the launcher and nulls it, which is precisely the state this decision exists to
 * recover from: read off `active`, the answer was no in exactly the state that needed a yes, and
 * the mode stayed dead for the life of the process however often the phone came back.
 *
 * The handshake questions do come from the launcher, so they are nullable here: a null launcher
 * has no handshake running and no attempt in flight, which is the answer that lets the re-arm
 * proceed. Both must be asked - a successful handoff closes the AA listeners, so an active
 * handshake and a settling handoff are different states and either one means tearing down would
 * interrupt work in progress.
 *
 * [sessionUp] covers the whole life of a working session, during which `isActive()` is false;
 * without it, any later ACL_CONNECTED (the phone's own Bluetooth profiles reconnecting, or one of
 * our own pokes) would tear down a session that is projecting fine.
 */
object BtAutoStartRearmPolicy {

    fun shouldRearm(
        mode: WifiLauncherMode,
        sessionUp: Boolean,
        handshakeActive: Boolean?,
        attemptInFlight: Boolean?
    ): Boolean =
        mode == WifiLauncherMode.NATIVE &&
            !sessionUp &&
            handshakeActive != true &&
            attemptInFlight != true
}
