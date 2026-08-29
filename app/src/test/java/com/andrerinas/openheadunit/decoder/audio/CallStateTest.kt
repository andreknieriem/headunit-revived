package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateTest {

    @Test
    fun `a cellular call is a call`() {
        assertTrue(
            CallState.isCallActive(
                audioMode = AudioManager.MODE_IN_CALL,
                appHoldsCommunicationMode = false
            )
        )
    }

    @Test
    fun `a voip call is a call`() {
        assertTrue(
            CallState.isCallActive(
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                appHoldsCommunicationMode = false
            )
        )
    }

    @Test
    fun `our own microphone uplink is not a call`() {
        assertFalse(
            CallState.isCallActive(
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                appHoldsCommunicationMode = true
            )
        )
    }

    @Test
    fun `the uplink does not mask a cellular call`() {
        assertTrue(
            CallState.isCallActive(
                audioMode = AudioManager.MODE_IN_CALL,
                appHoldsCommunicationMode = true
            )
        )
    }

    @Test
    fun `nothing playing is not a call`() {
        assertFalse(
            CallState.isCallActive(
                audioMode = AudioManager.MODE_NORMAL,
                appHoldsCommunicationMode = false
            )
        )
    }

    @Test
    fun `ringing is not yet a call`() {
        assertFalse(
            CallState.isCallActive(
                audioMode = AudioManager.MODE_RINGTONE,
                appHoldsCommunicationMode = false
            )
        )
        assertTrue(CallState.isCallStarting(audioMode = AudioManager.MODE_RINGTONE))
        assertFalse(CallState.isCallStarting(audioMode = AudioManager.MODE_NORMAL))
    }
}
