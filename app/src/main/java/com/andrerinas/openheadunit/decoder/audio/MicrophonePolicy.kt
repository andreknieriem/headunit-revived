package com.andrerinas.openheadunit.decoder.audio

/**
 * Whether this head unit records when the phone asks, and what to say when it will not.
 *
 * The announcement is not part of the decision, deliberately. Android Auto's required-service check
 * refuses a head unit that declares no microphone service - its own list reads "No audio/mic" and
 * the teardown reason is a missing microphone - so the service is announced unconditionally and the
 * only thing a user or a broken device can change is whether any PCM follows it.
 *
 * The two motorcycle requests get half of what they asked for. The physical microphone stays free
 * for a Bluetooth helmet intercom, but the phone does not take over: Android Auto picks its recorder
 * once at session start, and picks its own only for a head unit that declared itself a motorcycle,
 * which needs a vehicle type nothing here sends. Declining therefore leaves the assistant deaf.
 *
 * Pure: no Android, no logging.
 */
object MicrophonePolicy {

    /** Why a microphone request will not be honoured, or [Decline.NONE] if it will. */
    fun declineReason(headUnitMicEnabled: Boolean, recorderAvailable: Boolean): Decline = when {
        !headUnitMicEnabled -> Decline.USER_SETTING
        !recorderAvailable -> Decline.NO_MICROPHONE
        else -> Decline.NONE
    }

    fun shouldCapture(headUnitMicEnabled: Boolean, recorderAvailable: Boolean): Boolean =
        declineReason(headUnitMicEnabled, recorderAvailable) == Decline.NONE

    enum class Decline {
        /** Capture normally. */
        NONE,

        /** The user handed the microphone to the phone. Not a fault, and the log has to say so. */
        USER_SETTING,

        /** No usable capture configuration on this device. */
        NO_MICROPHONE
    }
}
