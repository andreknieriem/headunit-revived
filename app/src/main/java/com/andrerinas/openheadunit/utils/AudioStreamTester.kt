package com.andrerinas.openheadunit.utils

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.decoder.audio.AudioStreamCatalog

/**
 * Naming and audition for the output streams a channel can be played on.
 *
 * The list comes from [AudioStreamCatalog], so it is whatever this device actually has rather than
 * a fixed set - a head unit with a vendor stream gets it offered, under the name its own framework
 * reports. The whole point of the picker is that the right answer is head-unit specific and cannot
 * be reasoned about from here: on many units the amplifier only unmutes for some streams, so the
 * only way to know is to hear one. This plays a tone on the stream the user is currently looking
 * at, before the choice is saved and long before a phone is connected.
 */
object AudioStreamTester {

    /**
     * How long the test tone sounds.
     *
     * A full second, because the failure this button exists to find is an amplifier that does not
     * unmute for a stream, and car amplifiers routinely take a few hundred milliseconds to wake
     * after audio starts. Anything shorter can be swallowed whole by an amp still coming up, which
     * reads exactly like a stream that does not work.
     */
    private const val TONE_MS = 1000

    // One tone at a time. Comparing two streams means tapping one speaker button then the next,
    // and at a full second each the second tap would otherwise land on top of the first - two
    // tones at once tells you nothing about either, and the platform hands out a limited number
    // of generators, so the second tap could fail outright. Touched only from the UI thread.
    private var activeTone: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { stopActiveTone() }

    private fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Silences and frees whatever is playing, if anything. */
    private fun stopActiveTone() {
        handler.removeCallbacks(releaseRunnable)
        activeTone?.let {
            try {
                it.stopTone()
                it.release()
            } catch (e: RuntimeException) {
                AppLog.e("AudioStreamTester: failed to release the tone generator", e)
            }
        }
        activeTone = null
    }

    /** Streams this device accepts, in picker order. */
    fun streams(context: Context): List<AudioStreamCatalog.Entry> =
        AudioStreamCatalog.available(audioManager(context))

    /** Picker labels, in the same order as [streams]. */
    fun labels(context: Context): Array<String> =
        streams(context).map { label(context, it) }.toTypedArray()

    /** Position of a stored stream in the picker, or 0 when this device does not have it. */
    fun indexOf(context: Context, stream: Int): Int =
        streams(context).indexOfFirst { it.id == stream }.coerceAtLeast(0)

    fun streamAt(context: Context, index: Int): Int =
        streams(context).getOrNull(index)?.id ?: AudioManager.STREAM_MUSIC

    /**
     * How a stored stream reads in a row. A stream the device does not have still gets a label
     * rather than being silently shown as something else - that is what a settings backup carried
     * over from another head unit looks like, and it needs to be visible.
     */
    fun label(context: Context, stream: Int): String {
        val entry = streams(context).firstOrNull { it.id == stream }
            ?: return context.getString(R.string.audio_stream_unavailable_format, stream)
        return label(context, entry)
    }

    private fun label(context: Context, entry: AudioStreamCatalog.Entry): String {
        if (entry.isStandard) {
            standardNameRes(entry.id)?.let { return context.getString(it) }
        }
        val name = entry.constantName
            ?: return context.getString(R.string.audio_stream_unnamed_format, entry.id)
        // Vendor streams keep their platform name and carry the number, because that is how they
        // appear in logs and in whatever the head unit's own tooling calls them.
        return context.getString(R.string.audio_stream_vendor_format, name, entry.id)
    }

    /** How many of this device's streams are not stock Android. */
    fun vendorStreamCount(context: Context): Int = streams(context).count { !it.isStandard }

    private fun standardNameRes(id: Int): Int? = when (id) {
        AudioManager.STREAM_VOICE_CALL -> R.string.audio_stream_voice_call
        AudioManager.STREAM_SYSTEM -> R.string.audio_stream_system
        AudioManager.STREAM_RING -> R.string.audio_stream_ring
        AudioManager.STREAM_MUSIC -> R.string.audio_stream_music
        AudioManager.STREAM_ALARM -> R.string.audio_stream_alarm
        AudioManager.STREAM_NOTIFICATION -> R.string.audio_stream_notification
        6 -> R.string.audio_stream_bluetooth_sco
        7 -> R.string.audio_stream_system_enforced
        AudioManager.STREAM_DTMF -> R.string.audio_stream_dtmf
        9 -> R.string.audio_stream_tts
        10 -> R.string.audio_stream_accessibility
        11 -> R.string.audio_stream_assistant
        else -> null
    }

    /**
     * Plays a short tone on [stream] and says so when the stream is silenced, because a muted
     * stream and a stream the head unit never unmutes sound identical and only one of them is
     * worth changing the setting over.
     */
    fun play(context: Context, stream: Int) {
        val audioManager = audioManager(context)
        val volume = try {
            audioManager.getStreamVolume(stream)
        } catch (e: RuntimeException) {
            // The device does not have this stream - a stored value from another head unit.
            AppLog.e("AudioStreamTester: stream $stream is not available on this device", e)
            Toast.makeText(context, R.string.audio_stream_test_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (volume == 0) {
            Toast.makeText(
                context,
                context.getString(R.string.audio_stream_muted_warning, label(context, stream)),
                Toast.LENGTH_LONG
            ).show()
        }

        // Whatever the last tap started stops here, so the two are heard one after the other.
        stopActiveTone()

        val tone = try {
            ToneGenerator(stream, ToneGenerator.MAX_VOLUME)
        } catch (e: RuntimeException) {
            // The platform hands out a limited number of tone generators and refuses when the
            // audio hardware is busy; a failed audition must not take the settings screen down.
            AppLog.e("AudioStreamTester: tone generator unavailable for stream $stream", e)
            Toast.makeText(context, R.string.audio_stream_test_failed, Toast.LENGTH_SHORT).show()
            return
        }

        // A continuous tone, so TONE_MS is what actually governs the length. Tones that are
        // "limited in time by definition" - TONE_PROP_BEEP2 and the rest of the UI beeps - ignore
        // the duration and stop at their own, which for BEEP2 is 70ms of sound inside 270ms.
        // 350Hz+440Hz is also low enough to carry through car speakers, where a DTMF pair is shrill.
        tone.startTone(ToneGenerator.TONE_SUP_DIAL, TONE_MS)
        activeTone = tone
        // Released on a delay rather than immediately: releasing while the tone plays cuts it off.
        handler.postDelayed(releaseRunnable, (TONE_MS + 300).toLong())
    }
}
