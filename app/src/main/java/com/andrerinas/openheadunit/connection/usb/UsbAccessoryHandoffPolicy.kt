package com.andrerinas.openheadunit.connection.usb

/**
 * Budgets for the window between `ACC_REQ_START` and the accessory interface being claimed.
 *
 * The permission wait exists because `UsbProfileGroupSettingsManager.deviceAttached()` broadcasts
 * the attach before `resolveActivity()` grants the device, so our own receiver can reach the
 * re-enumerated 0x2D00 first. Polling is a local workaround for that ordering, not a blessed
 * pattern: after the budget we fall back to the ordinary permission dialog.
 */
object UsbAccessoryHandoffPolicy {

    /** Re-check `hasPermission` on this cadence rather than raising a dialog straight away. */
    const val PERMISSION_POLL_INTERVAL_MS = 200L

    /**
     * Give up and ask the user after this long. Must stay under
     * [UsbLauncherManager.ATTACH_FALLBACK_DELAY_MS] so the retry resolves before the fallback that
     * would start a competing switch.
     */
    const val PERMISSION_POLL_BUDGET_MS = 1_000L

    /**
     * How long a switch claim stays live without being released. Sized to outlast a worst-case
     * switch (eight control transfers at [UsbAccessoryMode] timeout, plus the settle), because the
     * activity that stakes it is `noHistory` and can be finished before its release runs.
     */
    const val SWITCH_CLAIM_TTL_MS = 10_000L

    fun shouldKeepPollingForPermission(elapsedMs: Long): Boolean =
        elapsedMs < PERMISSION_POLL_BUDGET_MS

    /** A claim staked at [claimUntilMs] is honoured until it expires, so a dead claimer frees it. */
    fun switchClaimIsLive(nowMs: Long, claimUntilMs: Long): Boolean = nowMs < claimUntilMs

    fun claimExpiryFrom(nowMs: Long): Long = nowMs + SWITCH_CLAIM_TTL_MS
}
