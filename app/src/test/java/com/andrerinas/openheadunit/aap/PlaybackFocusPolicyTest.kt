package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.PlaybackFocusPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFocusPolicyTest {

    private fun acquire(
        mode: Mode = Mode.AUTO,
        staticAudioFocus: Boolean = false,
        audioSinkEnabled: Boolean = true,
        isAudioChannel: Boolean = true,
        btMediaLinkActive: Boolean = false,
        selfDefeatingLatched: Boolean = false
    ) = PlaybackFocusPolicy.shouldAcquire(
        mode = mode,
        staticAudioFocus = staticAudioFocus,
        audioSinkEnabled = audioSinkEnabled,
        isAudioChannel = isAudioChannel,
        btMediaLinkActive = btMediaLinkActive,
        selfDefeatingLatched = selfDefeatingLatched
    )

    // --- the pre-existing gates, which no mode may override ---

    @Test
    fun `static focus mode never uses the dynamic path`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquire(mode = mode, staticAudioFocus = true))
        }
    }

    @Test
    fun `a disabled audio sink never takes focus`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquire(mode = mode, audioSinkEnabled = false))
        }
    }

    @Test
    fun `non-audio channels never take focus`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquire(mode = mode, isAudioChannel = false))
        }
    }

    // --- AUTO ---

    @Test
    fun `auto takes focus when no bluetooth media link is up`() {
        // The car-radio case this feature was written for: nothing on Bluetooth, so the player we
        // silence is genuinely a local one.
        assertTrue(acquire(mode = Mode.AUTO, btMediaLinkActive = false))
    }

    @Test
    fun `auto declines while a bluetooth media link is up`() {
        // Taking focus here makes the A2DP sink AVRCP-pause the phone that is feeding us.
        assertFalse(acquire(mode = Mode.AUTO, btMediaLinkActive = true))
    }

    @Test
    fun `auto declines once the latch has tripped, even with no bluetooth link detected`() {
        // The backstop for units where the Bluetooth probe reports nothing useful.
        assertFalse(acquire(mode = Mode.AUTO, btMediaLinkActive = false, selfDefeatingLatched = true))
    }

    // --- ALWAYS / NEVER ---

    @Test
    fun `always keeps taking focus whatever the detection says`() {
        assertTrue(acquire(mode = Mode.ALWAYS, btMediaLinkActive = true))
        assertTrue(acquire(mode = Mode.ALWAYS, selfDefeatingLatched = true))
        assertTrue(acquire(mode = Mode.ALWAYS, btMediaLinkActive = true, selfDefeatingLatched = true))
    }

    @Test
    fun `never declines even when the detection is clean`() {
        assertFalse(acquire(mode = Mode.NEVER, btMediaLinkActive = false, selfDefeatingLatched = false))
    }

    // --- static mode's permanent grab ---

    private fun acquirePermanent(
        mode: Mode = Mode.AUTO,
        staticAudioFocus: Boolean = true,
        audioSinkEnabled: Boolean = true,
        btMediaLinkActive: Boolean = false
    ) = PlaybackFocusPolicy.shouldAcquirePermanent(
        mode = mode,
        staticAudioFocus = staticAudioFocus,
        audioSinkEnabled = audioSinkEnabled,
        btMediaLinkActive = btMediaLinkActive
    )

    @Test
    fun `the permanent grab belongs to static mode alone`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquirePermanent(mode = mode, staticAudioFocus = false))
        }
    }

    @Test
    fun `a disabled audio sink never takes permanent focus`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquirePermanent(mode = mode, audioSinkEnabled = false))
        }
    }

    @Test
    fun `auto takes permanent focus when no bluetooth media link is up`() {
        // Static mode's reason for existing — a generic head unit that loses audio routing —
        // is untouched for everyone this bug does not affect.
        assertTrue(acquirePermanent(mode = Mode.AUTO, btMediaLinkActive = false))
    }

    @Test
    fun `auto declines the permanent grab while a bluetooth media link is up`() {
        assertFalse(acquirePermanent(mode = Mode.AUTO, btMediaLinkActive = true))
    }

    @Test
    fun `always and never override the probe on the permanent path too`() {
        assertTrue(acquirePermanent(mode = Mode.ALWAYS, btMediaLinkActive = true))
        assertFalse(acquirePermanent(mode = Mode.NEVER, btMediaLinkActive = false))
    }

    @Test
    fun `the two paths are exact complements and never both take focus`() {
        // Same shape as WifiModePolicy vs UserExitHotspotPolicy: one decision, split across two
        // functions, so the split has to be airtight rather than merely plausible.
        for (mode in Mode.values()) {
            for (static in listOf(false, true)) {
                for (sink in listOf(false, true)) {
                    for (bt in listOf(false, true)) {
                        for (latched in listOf(false, true)) {
                            val dynamic = PlaybackFocusPolicy.shouldAcquire(
                                mode = mode,
                                staticAudioFocus = static,
                                audioSinkEnabled = sink,
                                isAudioChannel = true,
                                btMediaLinkActive = bt,
                                selfDefeatingLatched = latched
                            )
                            val permanent = acquirePermanent(
                                mode = mode,
                                staticAudioFocus = static,
                                audioSinkEnabled = sink,
                                btMediaLinkActive = bt
                            )
                            assertFalse(
                                "mode=$mode static=$static sink=$sink bt=$bt latched=$latched",
                                dynamic && permanent
                            )
                        }
                    }
                }
            }
        }
    }

    // --- the self-defeating detector ---

    @Test
    fun `a media channel stopping inside the window counts`() {
        assertTrue(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = 0L))
        assertTrue(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = 3_400L))
        assertTrue(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = 4_070L))
    }

    @Test
    fun `a media channel stopping outside the window does not count`() {
        assertFalse(
            PlaybackFocusPolicy.countsAsSelfDefeating(
                isMediaChannel = true,
                msSinceAcquire = PlaybackFocusPolicy.SELF_DEFEATING_WINDOW_MS
            )
        )
        assertFalse(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = 60_000L))
    }

    @Test
    fun `speech and system chimes never count, however short`() {
        // A navigation prompt is legitimately a second long; counting it would latch on one turn.
        assertFalse(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = false, msSinceAcquire = 900L))
    }

    @Test
    fun `a negative elapsed time does not count`() {
        // No acquire recorded yet, so there is nothing to attribute the stop to.
        assertFalse(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = -1L))
    }

    // --- mode round-tripping through settings ---

    @Test
    fun `modes round-trip through their stored value and unknown values fall back to auto`() {
        for (mode in Mode.values()) {
            assertEquals(mode, Mode.fromInt(mode.value))
        }
        assertEquals(Mode.AUTO, Mode.fromInt(-1))
        assertEquals(Mode.AUTO, Mode.fromInt(99))
    }

    @Test
    fun `auto is the stored default so an unset preference behaves like 3 point 1 point 1 plus detection`() {
        assertEquals(0, Mode.AUTO.value)
    }
}
