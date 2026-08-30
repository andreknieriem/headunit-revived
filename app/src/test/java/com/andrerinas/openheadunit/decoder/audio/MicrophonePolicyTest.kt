package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.decoder.audio.MicrophonePolicy.Decline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four combinations, and which of them is a fault. */
class MicrophonePolicyTest {

    @Test
    fun `a working microphone the user wants is used`() {
        assertEquals(Decline.NONE, MicrophonePolicy.declineReason(true, true))
        assertTrue(MicrophonePolicy.shouldCapture(true, true))
    }

    @Test
    fun `the setting wins over a working microphone`() {
        assertEquals(Decline.USER_SETTING, MicrophonePolicy.declineReason(false, true))
        assertFalse(MicrophonePolicy.shouldCapture(false, true))
    }

    @Test
    fun `a device with no usable capture declines for its own reason`() {
        assertEquals(Decline.NO_MICROPHONE, MicrophonePolicy.declineReason(true, false))
        assertFalse(MicrophonePolicy.shouldCapture(true, false))
    }

    @Test
    fun `the setting is reported ahead of the hardware`() {
        // Both are true at once on a device with no microphone whose owner also turned it off. The
        // setting is the one the user can act on, so it is the one named.
        assertEquals(Decline.USER_SETTING, MicrophonePolicy.declineReason(false, false))
    }
}
