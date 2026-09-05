package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Pure decision policy governing multi-driver / device selection in Native AA Wireless mode.
 *
 * All decisions regarding whether to show the selection dialog, which device to prioritize,
 * and how to resolve auto-connect timeouts are encapsulated here for testability.
 */
object NativeDriverSelectionPolicy {

    enum class Mode(val id: Int) {
        DISABLED(0),
        AUTO(1),
        ALWAYS(2);

        companion object {
            fun fromId(id: Int): Mode = entries.firstOrNull { it.id == id } ?: AUTO
        }
    }

    const val DEFAULT_TIMEOUT_SEC = 10
    const val MIN_TIMEOUT_SEC = 3
    const val MAX_TIMEOUT_SEC = 30

    /** How long past the countdown an on-screen prompt may hold the wake poke off. */
    const val PROMPT_GRACE_MS = 15_000L

    /**
     * How long a cancelled prompt keeps refusing incoming connections.
     *
     * One poke cycle, so a poke already on the wire when the user cancelled cannot pull them into
     * a session, and no longer: a phone dialling us afterwards is the driver asking for one.
     */
    const val CANCEL_REFUSAL_MS = 30_000L

    /**
     * How long a chosen driver's phone is the only one accepted once nothing is waking it.
     *
     * One poke cycle. The phone a switch moved away from can be back on the listeners in a second,
     * while the newly chosen one still has to be woken.
     */
    const val CHOSEN_EXCLUSIVE_MS = 30_000L

    /** How many times a chosen driver's phone is woken before the unit gives up on it. */
    const val CHOSEN_WAKE_ROUNDS = 3

    /**
     * The hard ceiling on refusing every other phone while the chosen one is woken.
     *
     * Three 20 s holds and two 15 s gaps is a 90 s wake budget, plus 30 s for the phone to join: a
     * cold single-phone connect measured about 26 s on the rig. Bounded so an unreachable choice
     * cannot deafen the unit for good.
     */
    const val CHOSEN_EXCLUSIVE_MAX_MS = 120_000L

    /**
     * How long the phone a switch moved away from is refused while no driver has been chosen yet.
     *
     * Long enough to read a selector and pick, short enough that an abandoned switch heals itself.
     */
    const val SWITCH_AWAY_REFUSAL_MS = 60_000L

    /**
     * How often a refusal that keeps repeating is said again.
     *
     * A refused phone retries RFCOMM every 150 ms or so, so the first line is the useful one and
     * the rest are noise. Restating with a count keeps a long refusal visible without the flood.
     */
    const val GATE_RESTATE_MS = 60_000L

    /** Whether a refusal already spoken for this phone is due to be said again. */
    fun shouldRestateRefusal(msSinceSpoken: Long): Boolean =
        msSinceSpoken >= GATE_RESTATE_MS

    /**
     * How long the wake poke defers to a prompt that is actually on screen.
     *
     * Bounded on purpose. A unit that starts with nobody in front of it must fall back to waking
     * every paired phone, which is what it did before the prompt existed.
     */
    fun promptDeferralMs(timeoutSec: Int): Long = sanitizeTimeout(timeoutSec) * 1000L + PROMPT_GRACE_MS

    /** What the wake poke loop should do about a driver prompt on this pass. */
    enum class PokeHold { GO, HOLD, EXPIRED }

    /**
     * Whether an on-screen driver prompt still holds the wake poke off.
     *
     * Asked on every pass of the poke loop, never once on entry: a deadline read only when
     * something else calls in measures that caller's cadence instead of itself.
     */
    fun pokeHold(
        promptActive: Boolean,
        targetChosen: Boolean,
        promptAgeMs: Long,
        timeoutSec: Int
    ): PokeHold = when {
        !promptActive || targetChosen -> PokeHold.GO
        promptAgeMs >= promptDeferralMs(timeoutSec) -> PokeHold.EXPIRED
        else -> PokeHold.HOLD
    }

    /**
     * The MAC to treat as the last driver, falling back to the wake poke list.
     *
     * The poke list is a set the user can put several phones in, so it stands in for a last-used
     * driver only when it names exactly one. Taking the first of several picks arbitrarily.
     */
    fun lastUsedMac(lastConnectedMac: String, pokeMacs: Set<String>): String =
        lastConnectedMac.ifEmpty { pokeMacs.singleOrNull().orEmpty() }

    /** Why a phone dialling in during a driver switch is refused, or that it is not. */
    enum class SwitchGate { ACCEPT, WRONG_PHONE, SWITCHED_AWAY }

    /**
     * Whether a phone dialling in is the driver the user actually chose.
     *
     * Reopening the Android Auto listeners for the phone's return lets the one just switched away
     * from win the race. Both windows are bounded: an unfinished switch must not deafen the unit.
     */
    fun switchGate(
        remoteMac: String,
        chosenMac: String?,
        chosenAgeMs: Long,
        switchedAwayFrom: String?,
        switchAgeMs: Long,
        chosenWakeActive: Boolean = false
    ): SwitchGate = when {
        remoteMac.isEmpty() -> SwitchGate.ACCEPT
        !chosenMac.isNullOrEmpty() && chosenExclusive(chosenAgeMs, chosenWakeActive) ->
            if (remoteMac.equals(chosenMac, ignoreCase = true)) SwitchGate.ACCEPT
            else SwitchGate.WRONG_PHONE
        !switchedAwayFrom.isNullOrEmpty() && switchAgeMs < SWITCH_AWAY_REFUSAL_MS &&
            remoteMac.equals(switchedAwayFrom, ignoreCase = true) -> SwitchGate.SWITCHED_AWAY
        else -> SwitchGate.ACCEPT
    }

    /**
     * Whether the chosen driver is still the only phone the accept gate lets in.
     *
     * Tied to the wake rather than to a flat deadline: the phone gets one 20 s hold per round, so a
     * window that outran the wake handed the session back to the phone the driver had just left.
     */
    fun chosenExclusive(chosenAgeMs: Long, wakeActive: Boolean): Boolean =
        chosenAgeMs < CHOSEN_EXCLUSIVE_MAX_MS &&
            (wakeActive || chosenAgeMs < CHOSEN_EXCLUSIVE_MS)

    /**
     * Determines whether the driver/device selection dialog should be displayed.
     *
     * @param mode User preference mode (DISABLED, AUTO, ALWAYS)
     * @param pairedCount Total number of paired phone candidates
     * @param connectedCount Number of devices currently connected via Bluetooth
     * @param hasHistory Whether a preferred or previously connected device is recorded
     */
    fun shouldShowSelector(
        mode: Mode,
        pairedCount: Int,
        connectedCount: Int,
        hasHistory: Boolean = true
    ): Boolean {
        if (mode == Mode.DISABLED) return false
        if (pairedCount <= 1) return false

        return when (mode) {
            Mode.ALWAYS -> true
            Mode.AUTO -> {
                // If exactly one phone is already connected to the car's Bluetooth, we know
                // unambiguously which driver is in the vehicle, so skip the prompt!
                if (connectedCount == 1) {
                    false
                } else if (!hasHistory) {
                    // On first start with multiple paired phones and no history, prompt user to choose
                    true
                } else {
                    // Multiple paired phones and either 0 or multiple connected
                    true
                }
            }
            Mode.DISABLED -> false
        }
    }

    /**
     * Resolves the target device MAC to connect to when the selection timer expires without user interaction.
     *
     * Priority:
     * 1. If exactly 1 device is currently connected via Bluetooth in the car, choose it.
     * 2. If preferred device is specified and available, choose it (connected takes precedence over paired).
     * 3. If last-used device is specified and available, choose it (connected takes precedence over paired).
     * 4. If exactly 1 device is in the candidate paired list, choose it.
     * 5. Otherwise, return null (never blindly pick among multiple unknown devices).
     */
    fun resolveAutoConnectTarget(
        preferredMac: String,
        lastUsedMac: String,
        connectedMacs: List<String>,
        pairedMacs: List<String>
    ): String? {
        if (pairedMacs.isEmpty()) return null

        val validConnected = connectedMacs.filter { it in pairedMacs }

        // 1. If exactly one phone is confirmed connected in the vehicle, it takes priority
        if (validConnected.size == 1) {
            return validConnected[0]
        }

        // 2. Preferred MAC (if multiple connected and preferred is among them, or if none connected and preferred is paired)
        if (preferredMac.isNotEmpty()) {
            if (preferredMac in validConnected) {
                return preferredMac
            }
            if (validConnected.isEmpty() && preferredMac in pairedMacs) {
                return preferredMac
            }
        }

        // 3. Last used MAC (if multiple connected and last-used is among them, or if none connected and last-used is paired)
        if (lastUsedMac.isNotEmpty()) {
            if (lastUsedMac in validConnected) {
                return lastUsedMac
            }
            if (validConnected.isEmpty() && lastUsedMac in pairedMacs) {
                return lastUsedMac
            }
        }

        // 4. Exactly 1 candidate phone available
        if (pairedMacs.size == 1) {
            return pairedMacs[0]
        }

        // Multiple candidates, none or multiple connected without history/preference
        return null
    }

    /**
     * Whether a wake attempt on this phone may claim the full screen straight away.
     *
     * A phone with no live Bluetooth link has to be woken first and may never answer, so the
     * full-screen "Android Auto is starting" would be a claim nothing backs for the whole watchdog.
     */
    fun connectUiIsImmediate(targetMac: String, connectedMacs: Collection<String>): Boolean =
        targetMac.isNotEmpty() && connectedMacs.any { it.equals(targetMac, ignoreCase = true) }

    /** What the selector may say about a phone's Bluetooth link. */
    enum class DriverPresence {
        /** A link is up, so this phone can answer now. */
        CONNECTED,

        /** No link, so this phone has to be woken and may not answer. */
        DISCONNECTED,

        /** The read is unavailable, so the selector says nothing rather than guessing. */
        UNKNOWN
    }

    /**
     * What to tell the driver about [targetMac] before they pick it.
     *
     * [readable] is false where the connection read cannot answer at all, below API 21 among others.
     * Calling every phone disconnected there would be worse than saying nothing, because the whole
     * list would read as unreachable while the phones are sitting connected.
     */
    fun presenceOf(
        targetMac: String,
        connectedMacs: Collection<String>,
        readable: Boolean
    ): DriverPresence = when {
        !readable || targetMac.isEmpty() -> DriverPresence.UNKNOWN
        connectedMacs.any { it.equals(targetMac, ignoreCase = true) } -> DriverPresence.CONNECTED
        else -> DriverPresence.DISCONNECTED
    }

    /**
     * Whether a live hands-free link belongs to some phone other than [targetMac].
     *
     * A switch leaves the outgoing phone's link up, and the wake guard reads that link adapter-wide,
     * so without this it stands down the poke aimed at the incoming phone and strands the switch.
     * Bounded by [CHOSEN_EXCLUSIVE_MAX_MS] so an abandoned switch heals itself.
     */
    fun handsFreeLinkIsAnotherPhones(
        targetMac: String,
        switchedAwayFrom: String?,
        switchAgeMs: Long
    ): Boolean {
        if (switchedAwayFrom.isNullOrEmpty() || targetMac.isEmpty()) return false
        if (switchAgeMs < 0 || switchAgeMs >= CHOSEN_EXCLUSIVE_MAX_MS) return false
        return !targetMac.equals(switchedAwayFrom, ignoreCase = true)
    }

    /**
     * Constrains timeout to [MIN_TIMEOUT_SEC]..[MAX_TIMEOUT_SEC].
     */
    fun sanitizeTimeout(timeoutSec: Int): Int {
        return timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
    }
}
