package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.input.MediaKeyRoutingPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKeyRoutingPolicyTest {

    private fun forward(mode: Mode, isMediaKey: Boolean = true, bt: Boolean? = null) =
        MediaKeyRoutingPolicy.shouldForward(mode, isMediaKey, bt)

    @Test
    fun `everything that is not a media button is always forwarded`() {
        for (mode in Mode.values()) {
            for (bt in listOf(true, false, null)) {
                assertTrue("mode=$mode bt=$bt", forward(mode, isMediaKey = false, bt = bt))
            }
        }
    }

    @Test
    fun `always forwards whatever bluetooth is doing`() {
        for (bt in listOf(true, false, null)) {
            assertTrue("bt=$bt", forward(Mode.ALWAYS, bt = bt))
        }
    }

    @Test
    fun `never suppresses whatever bluetooth is doing`() {
        for (bt in listOf(true, false, null)) {
            assertFalse("bt=$bt", forward(Mode.NEVER, bt = bt))
        }
    }

    @Test
    fun `auto suppresses only while a bluetooth media link is up`() {
        assertFalse(forward(Mode.AUTO, bt = true))
        assertTrue(forward(Mode.AUTO, bt = false))
    }

    @Test
    fun `auto forwards when the bluetooth state cannot be read`() {
        // The opposite resolution to PlaybackFocusPolicy's unknown case, on purpose: a doubled skip
        // is an annoyance, media buttons that silently do nothing read as a broken app.
        assertTrue(forward(Mode.AUTO, bt = null))
    }

    @Test
    fun `an unset or damaged preference means always`() {
        assertEquals(Mode.ALWAYS, Mode.fromInt(0))
        assertEquals(Mode.ALWAYS, Mode.fromInt(-1))
        assertEquals(Mode.ALWAYS, Mode.fromInt(99))
        assertEquals(Mode.AUTO, Mode.fromInt(1))
        assertEquals(Mode.NEVER, Mode.fromInt(2))
    }
}
