package com.andrerinas.openheadunit.aap.protocol.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSinkAnnouncementPolicyTest {

    /**
     * The field failure: enable-audio-sink was on, the session was Native AA over WiFi Direct, and
     * only the system channel was announced because a failed Self Mode launch had left its flag set.
     */
    @Test
    fun `a session that is not Self Mode announces both sinks when the setting is on`() {
        assertTrue(
            AudioSinkAnnouncementPolicy.announcesMediaAndSpeech(
                sinkEnabled = true,
                isSelfModeSession = false
            )
        )
    }

    /** Self Mode's own session keeps today's behaviour; whether it should is a separate question. */
    @Test
    fun `a Self Mode session still drops both sinks`() {
        assertFalse(
            AudioSinkAnnouncementPolicy.announcesMediaAndSpeech(
                sinkEnabled = true,
                isSelfModeSession = true
            )
        )
    }

    /** The setting wins over the transport: off means off on every session. */
    @Test
    fun `the sink setting being off drops both sinks whatever the session is`() {
        assertFalse(
            AudioSinkAnnouncementPolicy.announcesMediaAndSpeech(
                sinkEnabled = false,
                isSelfModeSession = false
            )
        )
        assertFalse(
            AudioSinkAnnouncementPolicy.announcesMediaAndSpeech(
                sinkEnabled = false,
                isSelfModeSession = true
            )
        )
    }
}
