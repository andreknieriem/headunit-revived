package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioManager

/**
 * Whether a phone call is up, read from the audio mode.
 *
 * The app has no telephony listener and `READ_PHONE_STATE` would cost a runtime prompt, while
 * `AudioManager.getMode()` needs no permission and covers VoIP as well as cellular. The one false
 * positive is our own microphone uplink, which sets `MODE_IN_COMMUNICATION` to force SCO routing.
 */
object CallState {

    /**
     * @param appHoldsCommunicationMode whether our own microphone path is the one holding
     *   `MODE_IN_COMMUNICATION`, in which case the mode says nothing about a call.
     */
    fun isCallActive(audioMode: Int, appHoldsCommunicationMode: Boolean): Boolean = when (audioMode) {
        AudioManager.MODE_IN_CALL -> true
        AudioManager.MODE_IN_COMMUNICATION -> !appHoldsCommunicationMode
        else -> false
    }

    /**
     * A call is ringing but has not been answered.
     *
     * Deliberately not part of [isCallActive]: the call screen takes over at answer, not at ring,
     * so this only exists to let an episode open a moment early and wait for the mode to catch up.
     */
    fun isCallStarting(audioMode: Int): Boolean = audioMode == AudioManager.MODE_RINGTONE
}
