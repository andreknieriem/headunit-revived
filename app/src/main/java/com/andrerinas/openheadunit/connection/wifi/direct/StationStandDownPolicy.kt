package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether to drop this unit's own WiFi association before creating the Native AA P2P group.
 *
 * On a single-radio chip an associated station leaves the group owner no free channel, so
 * wpa_supplicant either forces the group onto the station's channel or, when a frequency was asked
 * for, refuses to create one at all. That is the shape of a unit whose group never forms and of one
 * whose picture stutters on a busy home channel.
 *
 * Unconditional on every Native AA WiFi Direct bring-up: the platform gate below and whether this
 * unit is joined to anything are the only questions. Bounded to the bring-up and reversed on
 * teardown. [StationCoexistencePolicy] keeps describing rather than prescribing regardless.
 *
 * Pure, so every combination is a unit test rather than a device.
 */
object StationStandDownPolicy {

    /** First Android whose framework refuses these calls to an ordinary app. */
    private const val FIRST_API_WITH_TARGET_SDK_GUARD = 29

    /** Last Android where holding the overlay permission still gets past that guard. */
    private const val LAST_API_WITH_OVERLAY_BYPASS = 34

    /**
     * Whether the platform will honour `disableNetwork()` at all.
     *
     * The guard is `WifiServiceImpl.isTargetSdkLessThanQOrPrivileged`, which lives in the *device's*
     * framework and arrived in Android 10 — so this app's target SDK does not decide it, the head
     * unit's version does. That guard also passes anything holding SYSTEM_ALERT_WINDOW, which this
     * app already asks for, and that bypass was removed again in Android 15.
     */
    fun isAvailable(sdkInt: Int, canDrawOverlays: Boolean): Boolean = when {
        sdkInt < FIRST_API_WITH_TARGET_SDK_GUARD -> true
        sdkInt <= LAST_API_WITH_OVERLAY_BYPASS -> canDrawOverlays
        else -> false
    }

    /**
     * Whether to stand the station down now.
     *
     * @param networkId `WifiInfo.getNetworkId()`, which is -1 both when nothing is joined and when
     *   the caller cannot satisfy the location gate. Either way there is no network to name, and
     *   guessing one would disable a network the user never joined.
     */
    fun shouldStandDown(
        sdkInt: Int,
        canDrawOverlays: Boolean,
        associated: Boolean,
        networkId: Int
    ): Boolean = associated &&
        networkId >= 0 &&
        isAvailable(sdkInt, canDrawOverlays)

    /**
     * Why the stand-down is not going to happen on this unit, or null when it will.
     *
     * Logged rather than shown: on 29-34 the answer is a permission the user can actually grant,
     * and above that it is a fact about the unit worth having in a bug report.
     */
    fun describeUnavailable(sdkInt: Int, canDrawOverlays: Boolean): String? = when {
        isAvailable(sdkInt, canDrawOverlays) -> null
        sdkInt <= LAST_API_WITH_OVERLAY_BYPASS ->
            "This unit's Android will only let the app drop its own WiFi connection while the app " +
                "has the \"display over other apps\" permission. Granting it frees the group's radio."
        else ->
            "Android $sdkInt does not let an app drop this unit's own WiFi connection, so the " +
                "group has to share its channel. Disconnecting this unit's WiFi by hand before " +
                "connecting is the only way to free it."
    }

    /**
     * Whether a recorded stand-down still has to be undone.
     *
     * Unconditional on purpose. A record with no matching restore leaves the unit unable to rejoin
     * the owner's home network with nothing in the app saying why, which is the worst outcome this
     * whole feature can produce - so every teardown restores, and so does the next start.
     */
    fun shouldRestore(networkId: Int): Boolean = networkId >= 0
}
