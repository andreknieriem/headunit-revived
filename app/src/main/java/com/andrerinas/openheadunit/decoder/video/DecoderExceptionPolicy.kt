package com.andrerinas.openheadunit.decoder.video

/**
 * What an exception out of the running codec is worth, so the output thread stops treating them all
 * alike.
 *
 * `MediaCodec.CodecException` has said since API 21 whether a failure is transient (the component is
 * busy and the same call will work again) or recoverable (the codec must be reconfigured but the
 * frames so far are fine). This project has never read either flag: every exception fell into one
 * generic catch, counted toward the same three-strike counter, and the third one bought a full
 * release and recreate - the most expensive response available, on units where a single hardware
 * decoder instance means every leaked one fails the next create, and where a rebuild costs a GOP of
 * keyframe starvation.
 *
 * So a component that stutters once pays the same price as one that has genuinely died, and one that
 * has genuinely died waits out two more failures and 100ms of sleep first.
 */
object DecoderExceptionPolicy {

    /**
     * What the output thread should do with the exception it just caught.
     *
     * [CONTINUE] does not touch the strike counter: a transient failure is the component saying it
     * was busy, and three of those in a row is still not a fault.
     */
    enum class Response {
        /** Log it and carry on, without counting a strike. */
        CONTINUE,

        /** Count a strike and retry, restarting once the counter fills. The pre-existing behaviour. */
        COUNT_STRIKE,

        /** Restart now: this component has said it cannot recover, so the retries cannot help. */
        RESTART_NOW,
    }

    /**
     * [isCodecException] false means an exception this cannot classify, which takes the old path.
     *
     * Unknown is deliberately [Response.COUNT_STRIKE] rather than anything cheaper. The three-strike
     * counter is what has been shipping, and a policy that made an unrecognised failure *less* likely
     * to be repaired would be a regression dressed as a refinement.
     */
    fun responseTo(
        isCodecException: Boolean,
        isTransient: Boolean,
        isRecoverable: Boolean,
    ): Response = when {
        !isCodecException -> Response.COUNT_STRIKE
        isTransient -> Response.CONTINUE
        isRecoverable -> Response.COUNT_STRIKE
        else -> Response.RESTART_NOW
    }

    /**
     * How the classification reads in a log a reporter pastes into an issue.
     *
     * Worth spelling out rather than printing the enum: the difference between a codec that was busy
     * and a codec that is gone is the first thing anyone reading a stall wants, and it has never been
     * in a capture.
     */
    fun describe(response: Response): String = when (response) {
        Response.CONTINUE -> "the component says it was only busy"
        Response.COUNT_STRIKE -> "the component can be reconfigured"
        Response.RESTART_NOW -> "the component says it cannot recover"
    }
}
