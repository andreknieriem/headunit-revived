package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.decoder.video.DecoderExceptionPolicy.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DecoderExceptionPolicyTest {

    @Test
    fun `a busy component costs no strike`() {
        assertEquals(
            Response.CONTINUE,
            DecoderExceptionPolicy.responseTo(isCodecException = true, isTransient = true, isRecoverable = false)
        )
    }

    @Test
    fun `transient wins over recoverable when a component claims both`() {
        // Nothing forbids a component setting both flags, and the cheaper reading is the safe one:
        // a restart that was not needed costs a rebuild and a GOP of waiting for a keyframe, where a
        // retry that was not enough costs one more pass round the loop.
        assertEquals(
            Response.CONTINUE,
            DecoderExceptionPolicy.responseTo(isCodecException = true, isTransient = true, isRecoverable = true)
        )
    }

    @Test
    fun `a recoverable failure takes the path that was already shipping`() {
        assertEquals(
            Response.COUNT_STRIKE,
            DecoderExceptionPolicy.responseTo(isCodecException = true, isTransient = false, isRecoverable = true)
        )
    }

    @Test
    fun `a component that says it cannot recover is not asked twice more`() {
        assertEquals(
            Response.RESTART_NOW,
            DecoderExceptionPolicy.responseTo(isCodecException = true, isTransient = false, isRecoverable = false)
        )
    }

    @Test
    fun `an exception that cannot be classified keeps the old behaviour`() {
        for (transient in listOf(true, false)) {
            for (recoverable in listOf(true, false)) {
                assertEquals(
                    "flags on a non-CodecException must not be read",
                    Response.COUNT_STRIKE,
                    DecoderExceptionPolicy.responseTo(false, transient, recoverable)
                )
            }
        }
    }

    @Test
    fun `every response says something different in the log`() {
        val described = Response.values().map { DecoderExceptionPolicy.describe(it) }
        assertEquals(described.size, described.toSet().size)
        for (text in described) assertNotEquals("", text)
    }
}
