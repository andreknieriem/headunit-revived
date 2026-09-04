package com.andrerinas.openheadunit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtAutoDisconnectPolicyTest {

    private val car = "AA:BB:CC:DD:EE:01"
    private val phone = "AA:BB:CC:DD:EE:02"
    private val watched = setOf(car)

    @Test
    fun `an unwatched device is ignored whichever way its link goes`() {
        assertEquals(BtAutoDisconnectArm.IGNORE, BtAutoDisconnectPolicy.onAclEvent(watched, phone, connected = false))
        assertEquals(BtAutoDisconnectArm.IGNORE, BtAutoDisconnectPolicy.onAclEvent(watched, phone, connected = true))
    }

    /** The device extra on the broadcast can be null; that is not a watched device leaving. */
    @Test
    fun `a null device address is ignored`() {
        assertEquals(BtAutoDisconnectArm.IGNORE, BtAutoDisconnectPolicy.onAclEvent(watched, null, connected = false))
    }

    /** The empty list is the off switch, as it is for the auto-start list. */
    @Test
    fun `an empty watch list ignores everything`() {
        assertEquals(BtAutoDisconnectArm.IGNORE, BtAutoDisconnectPolicy.onAclEvent(emptySet(), car, connected = false))
    }

    @Test
    fun `a watched device losing its link arms the timer`() {
        assertEquals(BtAutoDisconnectArm.ARM, BtAutoDisconnectPolicy.onAclEvent(watched, car, connected = false))
    }

    @Test
    fun `a watched device coming back cancels the timer`() {
        assertEquals(BtAutoDisconnectArm.CANCEL, BtAutoDisconnectPolicy.onAclEvent(watched, car, connected = true))
    }

    @Test
    fun `nothing is ended when nothing is projecting`() {
        assertFalse(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = false, sessionAgeMs = 600_000L, deviceCameBack = false))
    }

    /**
     * The Native handoff case: the handshake socket closes seconds into the session and the OS
     * reports the phone's link gone. A session that young is never ended on a Bluetooth event.
     */
    @Test
    fun `a session younger than the settle window is left alone`() {
        val limit = BtAutoDisconnectPolicy.MIN_SESSION_AGE_MS
        assertFalse(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = true, sessionAgeMs = limit - 1, deviceCameBack = false))
        assertTrue(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = true, sessionAgeMs = limit, deviceCameBack = false))
    }

    @Test
    fun `a device that came back during the grace delay saves the session`() {
        assertFalse(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = true, sessionAgeMs = 600_000L, deviceCameBack = true))
    }

    @Test
    fun `a watched device that stays away ends a settled session`() {
        assertTrue(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = true, sessionAgeMs = 600_000L, deviceCameBack = false))
    }

    @Test
    fun `zero seconds means immediately and an absurd delay is clamped`() {
        assertEquals(0L, BtAutoDisconnectPolicy.graceDelayMs(0))
        assertEquals(0L, BtAutoDisconnectPolicy.graceDelayMs(-5))
        assertEquals(5_000L, BtAutoDisconnectPolicy.graceDelayMs(5))
        assertEquals(3_600_000L, BtAutoDisconnectPolicy.graceDelayMs(99_999))
    }
}
