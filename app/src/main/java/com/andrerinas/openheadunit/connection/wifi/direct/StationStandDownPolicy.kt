package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether to drop this unit's own WiFi association before creating the Native AA P2P group.
 *
 * On a single-radio chip an associated station leaves the group owner no free channel, so
 * wpa_supplicant either forces the group onto the station's channel or, when a frequency was asked
 * for, refuses to create one at all. That is the shape of a unit whose group never forms and of one
 * whose picture stutters on a busy home channel.
 *
 * Bounded to the bring-up and reversed afterwards, because the same association is measured to be
 * the *good* state once a session is running: on this hardware class an unjoined unit lost the
 * picture for seconds at a time where a joined one ran clean. [StationCoexistencePolicy] is why this
 * is a setting the user turns on rather than something the app decides, and it keeps describing
 * rather than prescribing regardless of what happens here.
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
        enabled: Boolean,
        sdkInt: Int,
        canDrawOverlays: Boolean,
        associated: Boolean,
        networkId: Int
    ): Boolean = enabled &&
        associated &&
        networkId >= 0 &&
        isAvailable(sdkInt, canDrawOverlays)

    /**
     * Why a stand-down the user asked for is not going to happen, or null when it will.
     *
     * A toggle that silently does nothing is how a setting stops being trusted, and on 29-34 the
     * answer is a permission the user can actually grant.
     */
    fun describeUnavailable(sdkInt: Int, canDrawOverlays: Boolean): String? = when {
        isAvailable(sdkInt, canDrawOverlays) -> null
        sdkInt <= LAST_API_WITH_OVERLAY_BYPASS ->
            "This unit's Android will only let the app drop its own WiFi connection while the app " +
                "has the \"display over other apps\" permission. Granting it enables this."
        else ->
            "Android $sdkInt does not let an app drop this unit's own WiFi connection, so this " +
                "setting cannot do anything here. Disconnecting it by hand before connecting is " +
                "the only way to get the same effect."
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
