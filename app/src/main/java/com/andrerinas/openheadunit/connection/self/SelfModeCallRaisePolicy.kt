package com.andrerinas.openheadunit.connection.self

/**
 * Whether the projection should be put back on top of the phone's call screen, in Self Mode.
 *
 * Android Auto never enables its car-mode in-call service on Android 13+, so Telecom's swap - the
 * only thing that suppresses the Dialer during projection - cannot fire, and the Dialer covers the
 * projection. Android Auto's own call UI is live underneath it the whole time, so raising our
 * activity gives the user the call screen they wanted.
 *
 * Bounded on purpose: a few attempts and then silence, so a user who deliberately wants the Dialer
 * gets it, and one last attempt after the call in case Android's own back-stack restore does not
 * happen.
 */
object SelfModeCallRaisePolicy {

    /** How long to let the call screen settle before the first attempt. */
    const val FIRST_ATTEMPT_DELAY_MS = 600L

    /** Spacing between attempts while the call is up. */
    const val RETRY_INTERVAL_MS = 1_200L

    /**
     * Attempts allowed per call.
     *
     * The limit is what keeps this from becoming a fight. Once it is spent we stop pushing for the
     * rest of the call, so switching to the Dialer by hand sticks.
     */
    const val MAX_ATTEMPTS_PER_CALL = 3

    /**
     * How long after the call ends to wait before the final attempt.
     *
     * Android normally reveals the still-paused projection the instant the call screen finishes
     * itself, measured at 17 ms. This window lets that happen on its own; the attempt is only for
     * the devices where it does not.
     */
    const val POST_CALL_SETTLE_MS = 1_000L

    /**
     * How long an episode may wait for the audio mode to report a call.
     *
     * The cover and the call race, and the cover usually wins: the call screen is up as soon as the
     * call is added, while the audio mode only reports one once the call is dialling, which on an
     * outgoing call is seconds later. If no call shows up in this window, something else covered us
     * and we leave it alone.
     */
    const val CALL_CONFIRM_WINDOW_MS = 5_000L

    /**
     * How long an attempt keeps counting after the projection came back.
     *
     * A call screen that relaunches itself over us would otherwise get a fresh budget each time it
     * did, which is the one way this could turn into a loop.
     */
    const val ATTEMPT_CARRY_WINDOW_MS = 5_000L

    /** Tick spacing while attempts remain. */
    const val TICK_MS = 400L

    /** Tick spacing once the attempts are spent and the only thing left to notice is the call ending. */
    const val IDLE_TICK_MS = 2_000L

    enum class Action {
        /** Nothing to do yet. Keep ticking. */
        WAIT,

        /** Put the projection back on top. */
        RAISE,

        /** We are back, or there is nothing here to fix. Stop ticking. */
        DONE,
    }

    /** Whether a cover is one to watch for a call, and if not, why not. */
    enum class CoverVerdict(val reason: String) {
        /** Nothing in the way. */
        OPEN("watching for a call"),

        /** Home or Recents. Someone who chose to leave is not argued with. */
        USER_LEFT("the user left deliberately"),

        /** Anywhere else the call screen lands on the phone and the projection on the head unit. */
        NOT_SELF_MODE("not Self Mode"),

        PIP("picture-in-picture owns the screen"),

        DISABLED("the setting is off"),

        /** The episode already running keeps its own clock and budget. */
        ALREADY_OPEN("already watching this cover"),
    }

    /**
     * Whether this cover is one to watch.
     *
     * Says nothing about the audio mode on purpose: the cover routinely arrives before the call
     * registers, so the mode is something the episode observes rather than a condition for opening
     * one.
     */
    fun coverVerdict(
        userLeft: Boolean,
        selfMode: Boolean,
        pipActive: Boolean,
        enabled: Boolean,
        episodeOpen: Boolean,
    ): CoverVerdict = when {
        episodeOpen -> CoverVerdict.ALREADY_OPEN
        userLeft -> CoverVerdict.USER_LEFT
        !selfMode -> CoverVerdict.NOT_SELF_MODE
        pipActive -> CoverVerdict.PIP
        !enabled -> CoverVerdict.DISABLED
        else -> CoverVerdict.OPEN
    }

    /**
     * What the caller carries between ticks.
     *
     * @param startedAtMs when the projection was covered.
     * @param sawCallActive whether a call has been observed at all during this episode.
     * @param attempts attempts made while the call was up.
     * @param lastAttemptAtMs when the last attempt was made, or 0 if none.
     * @param callEndedAtMs when the call was first observed to have ended, or 0 while it is up.
     * @param postCallAttemptUsed whether the one attempt after the call has been made.
     */
    data class Episode(
        val startedAtMs: Long,
        val sawCallActive: Boolean = false,
        val attempts: Int = 0,
        val lastAttemptAtMs: Long = 0L,
        val callEndedAtMs: Long = 0L,
        val postCallAttemptUsed: Boolean = false,
    )

    /** Folds this tick's observation of the call into [episode]. Call before [decide]. */
    fun observe(episode: Episode, nowMs: Long, callActive: Boolean): Episode = when {
        callActive -> episode.copy(sawCallActive = true, callEndedAtMs = 0L)
        episode.sawCallActive && episode.callEndedAtMs == 0L -> episode.copy(callEndedAtMs = nowMs)
        else -> episode
    }

    /**
     * @param nowMs monotonic clock, `SystemClock.elapsedRealtime()` at the call site.
     * @param isForeground whether the projection activity is resumed again.
     * @param pipActive whether picture-in-picture owns the screen, in which case being covered is
     *   the point.
     */
    fun decide(
        nowMs: Long,
        episode: Episode,
        callActive: Boolean,
        isForeground: Boolean,
        pipActive: Boolean,
    ): Action {
        if (isForeground || pipActive) return Action.DONE

        if (callActive) {
            if (episode.attempts >= MAX_ATTEMPTS_PER_CALL) return Action.WAIT
            val dueAtMs = if (episode.attempts == 0) {
                episode.startedAtMs + FIRST_ATTEMPT_DELAY_MS
            } else {
                episode.lastAttemptAtMs + RETRY_INTERVAL_MS
            }
            return if (nowMs >= dueAtMs) Action.RAISE else Action.WAIT
        }

        if (!episode.sawCallActive) {
            return if (nowMs - episode.startedAtMs >= CALL_CONFIRM_WINDOW_MS) Action.DONE else Action.WAIT
        }

        if (episode.postCallAttemptUsed) return Action.DONE
        val endedAtMs = if (episode.callEndedAtMs > 0L) episode.callEndedAtMs else nowMs
        return if (nowMs - endedAtMs >= POST_CALL_SETTLE_MS) Action.RAISE else Action.WAIT
    }

    /** The episode to carry forward after an attempt at [nowMs]. */
    fun onRaised(episode: Episode, nowMs: Long, callActive: Boolean): Episode = episode.copy(
        attempts = if (callActive) episode.attempts + 1 else episode.attempts,
        lastAttemptAtMs = nowMs,
        postCallAttemptUsed = episode.postCallAttemptUsed || !callActive,
    )

    /**
     * Attempts to start a new episode with, given what the last one spent.
     *
     * Zero once the window has passed, so a call an hour later is never talked out of trying.
     */
    fun carriedAttempts(previousAttempts: Int, lastAttemptAtMs: Long, nowMs: Long): Int =
        if (lastAttemptAtMs > 0L && nowMs - lastAttemptAtMs <= ATTEMPT_CARRY_WINDOW_MS) previousAttempts else 0

    /** How long until the next tick. */
    fun nextTickDelayMs(episode: Episode): Long =
        if (episode.attempts >= MAX_ATTEMPTS_PER_CALL && episode.callEndedAtMs == 0L) IDLE_TICK_MS else TICK_MS

    /** Why a decision came out the way it did, for the log. */
    fun describe(action: Action, episode: Episode, callActive: Boolean, isForeground: Boolean): String =
        when (action) {
            Action.WAIT -> when {
                callActive && episode.attempts >= MAX_ATTEMPTS_PER_CALL ->
                    "attempts spent, leaving the call screen alone"
                callActive -> "waiting for the next attempt"
                !episode.sawCallActive -> "no call yet"
                else -> "waiting for the call screen to close on its own"
            }
            Action.RAISE ->
                if (callActive) "attempt ${episode.attempts + 1} of $MAX_ATTEMPTS_PER_CALL during the call"
                else "the call ended and the projection is still covered"
            Action.DONE -> when {
                isForeground -> "the projection is back in front"
                !episode.sawCallActive -> "whatever covered the projection was not a call"
                else -> "nothing left to do"
            }
        }
}
