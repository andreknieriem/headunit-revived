package com.andrerinas.openheadunit.aap

/**
 * When to stop bringing wireless up automatically because doing so appears to be crashing the
 * device.
 *
 * Auto-start on boot turns a single system-level crash into an endless cycle: the system dies, the
 * head unit soft-reboots, `LOCKED_BOOT_COMPLETED` arrives, we start, we bring up the wireless mode,
 * and it dies again. Measured on a reporter's MT8163 unit at a 20–26 s period across seven
 * consecutive processes in two separate logs, with nothing in between to break it.
 *
 * The app cannot see why the system died — the log ends mid-frame with no stack — so this counts
 * the only thing it can observe: boot-started runs that did not last. Three in a row and wireless
 * bring-up is skipped, leaving the rest of the app (USB especially) working and the user with a
 * notice explaining what to do.
 *
 * Pure so the counting rules are testable without Android; the persistence and the notice live in
 * [com.andrerinas.openheadunit.app.BootCompleteReceiver] and
 * [com.andrerinas.openheadunit.aap.AapService].
 */
object BootLoopPolicy {

    /** Boot-started runs that must fail in a row before wireless bring-up is paused. */
    const val STRIKES_BEFORE_PAUSE = 3

    /**
     * How long a boot-started run must survive to count as healthy.
     *
     * Chosen against the measured lifetimes: the crash-loop processes lived 8, 9, 10 and 15 s,
     * while the two runs that reached a real session lived 48 s and 173 s. Short enough that an
     * ordinary trip clears it, long enough that a unit dying on connect never does.
     */
    const val HEALTHY_RUN_MS = 30_000L

    /** The strike count after another boot-started run begins. */
    fun nextStrikes(current: Int): Int = if (current < 0) 1 else current + 1

    /** Whether to skip wireless bring-up on this run. */
    fun shouldPauseWireless(strikes: Int): Boolean = strikes >= STRIKES_BEFORE_PAUSE

    /**
     * Whether a run that lasted [runMs] counts as healthy, clearing the strikes.
     *
     * Deliberately not "did a session connect": on the reporter's unit one cycle reached a complete
     * projection session with audio playing and died anyway, so a session-based signal would reset
     * the count on every pass and the guard would never trip.
     */
    fun clearsStrikes(runMs: Long): Boolean = runMs >= HEALTHY_RUN_MS
}
