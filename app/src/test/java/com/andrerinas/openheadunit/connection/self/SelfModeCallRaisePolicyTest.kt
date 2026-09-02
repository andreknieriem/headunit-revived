package com.andrerinas.openheadunit.connection.self

import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.Action
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.ATTEMPT_CARRY_WINDOW_MS
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.CALL_CONFIRM_WINDOW_MS
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.CoverVerdict
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.Episode
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.FIRST_ATTEMPT_DELAY_MS
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.IDLE_TICK_MS
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.MAX_ATTEMPTS_PER_CALL
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.POST_CALL_SETTLE_MS
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.RETRY_INTERVAL_MS
import com.andrerinas.openheadunit.connection.self.SelfModeCallRaisePolicy.TICK_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfModeCallRaisePolicyTest {

    private val start = 10_000L

    private fun decide(
        nowMs: Long,
        episode: Episode,
        callActive: Boolean = true,
        isForeground: Boolean = false,
        pipActive: Boolean = false,
    ): Action = SelfModeCallRaisePolicy.decide(
        nowMs = nowMs,
        episode = SelfModeCallRaisePolicy.observe(episode, nowMs, callActive),
        callActive = callActive,
        isForeground = isForeground,
        pipActive = pipActive,
    )

    /** One tick of the loop the activity runs, so a whole call can be played out in a test. */
    private fun tick(episode: Episode, nowMs: Long, callActive: Boolean): Pair<Action, Episode> {
        val observed = SelfModeCallRaisePolicy.observe(episode, nowMs, callActive)
        val action = SelfModeCallRaisePolicy.decide(
            nowMs = nowMs,
            episode = observed,
            callActive = callActive,
            isForeground = false,
            pipActive = false,
        )
        val next = if (action == Action.RAISE) {
            SelfModeCallRaisePolicy.onRaised(observed, nowMs, callActive)
        } else {
            observed
        }
        return action to next
    }

    @Test
    fun `the first attempt waits for the call screen to settle`() {
        val episode = Episode(startedAtMs = start)
        assertEquals(Action.WAIT, decide(nowMs = start + FIRST_ATTEMPT_DELAY_MS - 1, episode = episode))
        assertEquals(Action.RAISE, decide(nowMs = start + FIRST_ATTEMPT_DELAY_MS, episode = episode))
    }

    @Test
    fun `attempts are spaced by the retry interval`() {
        val episode = Episode(startedAtMs = start, sawCallActive = true, attempts = 1, lastAttemptAtMs = start + 600)
        assertEquals(Action.WAIT, decide(nowMs = start + 600 + RETRY_INTERVAL_MS - 1, episode = episode))
        assertEquals(Action.RAISE, decide(nowMs = start + 600 + RETRY_INTERVAL_MS, episode = episode))
    }

    @Test
    fun `a call gets exactly three attempts and then silence`() {
        var episode = Episode(startedAtMs = start)
        var now = start
        var raises = 0
        repeat(120) {
            now += TICK_MS
            val (action, next) = tick(episode, now, callActive = true)
            if (action == Action.RAISE) raises++
            episode = next
        }
        assertEquals(MAX_ATTEMPTS_PER_CALL, raises)
        assertEquals(Action.WAIT, tick(episode, now + TICK_MS, callActive = true).first)
    }

    @Test
    fun `the projection coming back closes the episode`() {
        val episode = Episode(startedAtMs = start, sawCallActive = true, attempts = 1, lastAttemptAtMs = start)
        assertEquals(Action.DONE, decide(nowMs = start + 5_000, episode = episode, isForeground = true))
    }

    @Test
    fun `picture-in-picture closes the episode`() {
        val episode = Episode(startedAtMs = start, sawCallActive = true)
        assertEquals(Action.DONE, decide(nowMs = start + 5_000, episode = episode, pipActive = true))
    }

    @Test
    fun `something that is not a call is left alone`() {
        val episode = Episode(startedAtMs = start)
        assertEquals(
            Action.WAIT,
            decide(nowMs = start + CALL_CONFIRM_WINDOW_MS - 1, episode = episode, callActive = false)
        )
        assertEquals(
            Action.DONE,
            decide(nowMs = start + CALL_CONFIRM_WINDOW_MS, episode = episode, callActive = false)
        )
    }

    @Test
    fun `a call that registers late still opens the episode`() {
        var episode = Episode(startedAtMs = start)
        val (early, afterEarly) = tick(episode, start + TICK_MS, callActive = false)
        assertEquals(Action.WAIT, early)
        episode = afterEarly

        val (late, _) = tick(episode, start + FIRST_ATTEMPT_DELAY_MS, callActive = true)
        assertEquals(Action.RAISE, late)
    }

    @Test
    fun `the call ending gets one attempt, and only one`() {
        var episode = Episode(startedAtMs = start, sawCallActive = true, attempts = MAX_ATTEMPTS_PER_CALL)
        val endedAt = start + 30_000

        assertEquals(Action.WAIT, tick(episode, endedAt, callActive = false).first)
        episode = tick(episode, endedAt, callActive = false).second

        val (action, afterRaise) = tick(episode, endedAt + POST_CALL_SETTLE_MS, callActive = false)
        assertEquals(Action.RAISE, action)
        assertEquals(
            Action.DONE,
            tick(afterRaise, endedAt + POST_CALL_SETTLE_MS + TICK_MS, callActive = false).first
        )
    }

    @Test
    fun `the post-call attempt never happens when the projection came back on its own`() {
        val episode = Episode(
            startedAtMs = start,
            sawCallActive = true,
            attempts = MAX_ATTEMPTS_PER_CALL,
            callEndedAtMs = start + 30_000,
        )
        assertEquals(
            Action.DONE,
            decide(
                nowMs = start + 30_000 + POST_CALL_SETTLE_MS,
                episode = episode,
                callActive = false,
                isForeground = true,
            )
        )
    }

    @Test
    fun `the post-call attempt does not spend a call attempt`() {
        val episode = Episode(startedAtMs = start, sawCallActive = true, attempts = 1, callEndedAtMs = start + 5_000)
        val raised = SelfModeCallRaisePolicy.onRaised(episode, start + 6_000, callActive = false)
        assertEquals(1, raised.attempts)
        assertTrue(raised.postCallAttemptUsed)
    }

    @Test
    fun `a call screen that relaunches does not buy a second budget`() {
        val spentAtMs = start + 3_000
        assertEquals(
            MAX_ATTEMPTS_PER_CALL,
            SelfModeCallRaisePolicy.carriedAttempts(
                previousAttempts = MAX_ATTEMPTS_PER_CALL,
                lastAttemptAtMs = spentAtMs,
                nowMs = spentAtMs + ATTEMPT_CARRY_WINDOW_MS,
            )
        )
        assertEquals(
            Action.WAIT,
            decide(
                nowMs = spentAtMs + ATTEMPT_CARRY_WINDOW_MS,
                episode = Episode(
                    startedAtMs = spentAtMs + ATTEMPT_CARRY_WINDOW_MS,
                    sawCallActive = true,
                    attempts = MAX_ATTEMPTS_PER_CALL,
                    lastAttemptAtMs = spentAtMs,
                ),
            )
        )
    }

    @Test
    fun `a later call starts with a full budget`() {
        assertEquals(
            0,
            SelfModeCallRaisePolicy.carriedAttempts(
                previousAttempts = MAX_ATTEMPTS_PER_CALL,
                lastAttemptAtMs = start,
                nowMs = start + ATTEMPT_CARRY_WINDOW_MS + 1,
            )
        )
        assertEquals(
            0,
            SelfModeCallRaisePolicy.carriedAttempts(
                previousAttempts = MAX_ATTEMPTS_PER_CALL,
                lastAttemptAtMs = 0L,
                nowMs = start,
            )
        )
    }

    @Test
    fun `ticking slows down once the attempts are spent`() {
        val spending = Episode(startedAtMs = start, sawCallActive = true, attempts = MAX_ATTEMPTS_PER_CALL - 1)
        assertEquals(TICK_MS, SelfModeCallRaisePolicy.nextTickDelayMs(spending))

        val spent = spending.copy(attempts = MAX_ATTEMPTS_PER_CALL)
        assertEquals(IDLE_TICK_MS, SelfModeCallRaisePolicy.nextTickDelayMs(spent))

        val ended = spent.copy(callEndedAtMs = start + 30_000)
        assertEquals(TICK_MS, SelfModeCallRaisePolicy.nextTickDelayMs(ended))
    }

    /**
     * The reporter's cover: the call screen was up 82 ms after the tap and the audio mode did not
     * report the call for seconds. The episode has to survive that wait and then still raise.
     */
    @Test
    fun `a cover that becomes a call seconds later still raises`() {
        var episode = Episode(startedAtMs = start)

        var nowMs = start + TICK_MS
        while (nowMs < start + 3_000) {
            val (action, next) = tick(episode, nowMs, callActive = false)
            assertEquals(Action.WAIT, action)
            episode = next
            nowMs += TICK_MS
        }

        val (raise, after) = tick(episode, nowMs, callActive = true)
        assertEquals(Action.RAISE, raise)
        assertEquals(1, after.attempts)
        assertTrue(after.sawCallActive)
    }

    @Test
    fun `a cover is watched unless something says otherwise`() {
        assertEquals(CoverVerdict.OPEN, coverVerdict())
        assertEquals(CoverVerdict.USER_LEFT, coverVerdict(userLeft = true))
        assertEquals(CoverVerdict.NOT_SELF_MODE, coverVerdict(selfMode = false))
        assertEquals(CoverVerdict.PIP, coverVerdict(pipActive = true))
        assertEquals(CoverVerdict.DISABLED, coverVerdict(enabled = false))
        assertEquals(CoverVerdict.ALREADY_OPEN, coverVerdict(episodeOpen = true))
    }

    /** An episode already running owns the cover, whatever else is true of it. */
    @Test
    fun `an open episode is never restarted`() {
        assertEquals(
            CoverVerdict.ALREADY_OPEN,
            coverVerdict(userLeft = true, pipActive = true, episodeOpen = true)
        )
    }

    private fun coverVerdict(
        userLeft: Boolean = false,
        selfMode: Boolean = true,
        pipActive: Boolean = false,
        enabled: Boolean = true,
        episodeOpen: Boolean = false,
    ): CoverVerdict = SelfModeCallRaisePolicy.coverVerdict(
        userLeft = userLeft,
        selfMode = selfMode,
        pipActive = pipActive,
        enabled = enabled,
        episodeOpen = episodeOpen,
    )
}
