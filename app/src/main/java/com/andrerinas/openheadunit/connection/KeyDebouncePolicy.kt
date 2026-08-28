package com.andrerinas.openheadunit.connection

/**
 * Which of the several deliveries of one physical button press actually reaches Android Auto.
 *
 * A single press arrives at `CommManager.sendKey` more than once: during projection the OEM hands
 * the foreground activity a raw `KeyEvent` *and* broadcasts a media button, and the car-key
 * receivers add their own copies on top. Every extra delivery that gets through is an extra action
 * on the phone.
 *
 * The discriminator is the event's own identity. `KeyEvent.getDownTime()` is stamped when the
 * gesture began, so every redelivery of one press carries the same value and two real presses never
 * do — which is exact, needs no tuning, and lets a deliberate fast double-press through. The
 * time window survives only for the proprietary OEM broadcasts that carry no `KeyEvent` at all;
 * there, guessing from elapsed time is still all there is.
 *
 * Deciding a press means deciding the whole click, both edges. Releasing a key we never pressed is
 * as wrong as pressing it twice: Android Auto reads a stray release as the end of a gesture it does
 * not have, and a press whose release goes missing leaves the key held down forever.
 *
 * Pure and unit-tested; `CommManager` holds the per-key state and does the sending.
 */
object KeyDebouncePolicy {

    /**
     * Fallback windows for deliveries with no event identity, carried over unchanged from the
     * original inline debounce: media keys are the ones OEM head units fan out most.
     */
    const val MEDIA_WINDOW_MS = 600L
    const val DEFAULT_WINDOW_MS = 300L

    /**
     * How long a key may stay held before the next press is treated as a new one rather than a
     * duplicate of the one still down. Without this, a delivery path that sends a press and never
     * its release latches the key: every later press matches the held state and is dropped, and
     * nothing clears it short of a disconnect. Long enough not to break a genuine press-and-hold,
     * short enough that a user who presses again has their second press work.
     */
    const val STUCK_PRESS_MS = 2_000L

    /** Per logical key. Owned by the caller, replaced wholesale by each [decide]. */
    data class KeyState(
        /** A press has been forwarded and its release has not. */
        val down: Boolean = false,
        /** When the held press was forwarded, for [STUCK_PRESS_MS]. */
        val downAt: Long = 0L,
        /** When the last press was forwarded, for the fallback window. Null until there is one. */
        val lastForwardedPressAt: Long? = null,
        /** Identity of the last forwarded press, or null if that delivery carried none. */
        val lastForwardedDownTime: Long? = null,
        /** The press of the click in flight was dropped, so its release must be dropped too. */
        val pressDropped: Boolean = false
    )

    data class Result(
        val forward: Boolean,
        /**
         * Send a release for the previously held press before this one. Only set for a press that
         * arrives while a stale press is still held: the phone is owed the release of the gesture
         * it was told about.
         */
        val releaseFirst: Boolean = false,
        /** Why this delivery was dropped, for the log. Null when it was forwarded. */
        val dropReason: String? = null,
        val state: KeyState
    )

    fun decide(
        state: KeyState,
        isPress: Boolean,
        downTime: Long?,
        isMediaKey: Boolean,
        now: Long
    ): Result {
        // A synthetic KeyEvent built from (action, code) alone carries a zero downTime, and every
        // press would then look like a redelivery of the last one. No identity is better than a
        // constant one.
        val id = downTime?.takeIf { it > 0L }

        return if (isPress) decidePress(state, id, isMediaKey, now) else decideRelease(state)
    }

    private fun decidePress(state: KeyState, id: Long?, isMediaKey: Boolean, now: Long): Result {
        val last = state.lastForwardedDownTime
        if (id != null && last != null && id == last) {
            // Same physical press, arriving by another route. Nothing about the state changes: the
            // release of this press still has to be able to lift the key.
            return Result(forward = false, dropReason = "another delivery of the same key event", state = state)
        }

        // Identity settles it only when both sides have one. A press that carries identity following
        // one that did not (or the reverse) is the same press reaching us by two different routes at
        // least as often as it is a new one, so fall back to the window rather than assume.
        val identityProvesNewPress = id != null && last != null

        if (state.down) {
            // A press that carries a different identity is a different press: the redelivery of the
            // held one was already dropped above. Otherwise only time can tell a duplicate from a
            // press whose release went missing, and a key held this long is the latter.
            if (!identityProvesNewPress && now - state.downAt < STUCK_PRESS_MS) {
                return Result(forward = false, dropReason = "the key is already held down", state = state)
            }
            // Close the held press out and let this one through, whatever the window says — the
            // window drops duplicates, and this is not one.
            return Result(
                forward = true,
                releaseFirst = true,
                state = state.copy(
                    down = true,
                    downAt = now,
                    lastForwardedPressAt = now,
                    lastForwardedDownTime = id,
                    pressDropped = false
                )
            )
        }

        val lastPressAt = state.lastForwardedPressAt
        if (!identityProvesNewPress && lastPressAt != null) {
            val window = if (isMediaKey) MEDIA_WINDOW_MS else DEFAULT_WINDOW_MS
            val since = now - lastPressAt
            if (since < window) {
                // Drop the click as a unit. Marking the key down here — as the original code did
                // before returning — is what put an unmatched release on the wire.
                return Result(
                    forward = false,
                    dropReason = "duplicate ${since}ms after the last press, within ${window}ms",
                    state = state.copy(pressDropped = true)
                )
            }
        }

        return Result(
            forward = true,
            state = state.copy(
                down = true,
                downAt = now,
                lastForwardedPressAt = now,
                lastForwardedDownTime = id,
                pressDropped = false
            )
        )
    }

    private fun decideRelease(state: KeyState): Result {
        if (state.pressDropped) {
            return Result(
                forward = false,
                dropReason = "its press was dropped",
                state = state.copy(pressDropped = false)
            )
        }
        if (!state.down) {
            return Result(forward = false, dropReason = "no press is outstanding", state = state)
        }
        return Result(forward = true, state = state.copy(down = false))
    }
}
