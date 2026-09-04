package com.andrerinas.openheadunit.connection.self

/**
 * What a `Disconnected` arriving while Self Mode is armed may take down.
 *
 * [SelfLauncherManager.isActive] is set before the launchers run, so a launcher's own failed dial
 * arrives as a disconnect on a session that never existed. Reading that as "the Self Mode session
 * ended" stopped the wireless launcher mid-bring-up and churned the P2P group.
 */
object SelfModeDisconnectPolicy {

    /**
     * Only a Self Mode session that actually ran owns the wireless launcher's teardown. While the
     * launchers are still going, the disconnect is the attempt failing and belongs to nobody else.
     */
    fun stopsWirelessLauncher(selfModeActive: Boolean, launchInFlight: Boolean): Boolean =
        selfModeActive && !launchInFlight
}
