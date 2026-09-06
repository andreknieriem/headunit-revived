package com.andrerinas.openheadunit.connection.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The budgets that decide whether we win the accessory-mode window. */
class UsbAccessoryHandoffPolicyTest {

    @Test
    fun `permission is re-checked while the budget lasts`() {
        assertTrue(UsbAccessoryHandoffPolicy.shouldKeepPollingForPermission(elapsedMs = 0L))
        assertTrue(UsbAccessoryHandoffPolicy.shouldKeepPollingForPermission(elapsedMs = 999L))
    }

    @Test
    fun `permission polling stops at the budget, so the dialog is still reachable`() {
        assertFalse(UsbAccessoryHandoffPolicy.shouldKeepPollingForPermission(elapsedMs = 1_000L))
        assertFalse(UsbAccessoryHandoffPolicy.shouldKeepPollingForPermission(elapsedMs = 5_000L))
    }

    /**
     * The retry has to resolve before the attach fallback would fire, or the fallback starts a
     * second AOA switch on a device we are already waiting on.
     */
    @Test
    fun `the permission budget fits inside the attach fallback delay`() {
        assertTrue(
            UsbAccessoryHandoffPolicy.PERMISSION_POLL_BUDGET_MS <
                UsbLauncherManager.ATTACH_FALLBACK_DELAY_MS
        )
    }

    @Test
    fun `a fresh claim is live and an expired one is not`() {
        val now = 10_000L
        val until = UsbAccessoryHandoffPolicy.claimExpiryFrom(now)

        assertTrue(UsbAccessoryHandoffPolicy.switchClaimIsLive(nowMs = now, claimUntilMs = until))
        assertTrue(UsbAccessoryHandoffPolicy.switchClaimIsLive(nowMs = until - 1, claimUntilMs = until))
        assertFalse(UsbAccessoryHandoffPolicy.switchClaimIsLive(nowMs = until, claimUntilMs = until))
    }

    /** A released claim is stored as 0, which must never read as live. */
    @Test
    fun `a released claim is not live`() {
        assertFalse(UsbAccessoryHandoffPolicy.switchClaimIsLive(nowMs = 0L, claimUntilMs = 0L))
        assertFalse(UsbAccessoryHandoffPolicy.switchClaimIsLive(nowMs = 1_000L, claimUntilMs = 0L))
    }

    /**
     * The claim has to outlive the switch it guards, or an activity finished by `noHistory` before
     * it could release leaves the attach fallback free to start a second switch mid-flight.
     */
    @Test
    fun `the claim TTL outlasts the switch it covers`() {
        assertTrue(
            UsbAccessoryHandoffPolicy.SWITCH_CLAIM_TTL_MS >
                UsbAccessoryHandoffPolicy.PERMISSION_POLL_BUDGET_MS
        )
        assertTrue(
            UsbAccessoryHandoffPolicy.SWITCH_CLAIM_TTL_MS >
                UsbLauncherManager.ATTACH_FALLBACK_DELAY_MS
        )
    }
}
