package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioManager
import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The output streams this particular device actually has, discovered at runtime.
 *
 * A fixed table cannot answer this. The AOSP set grew with the platform - KitKat and older stop at
 * TTS (9), API 26 adds ACCESSIBILITY (10), API 30 adds ASSISTANT (11) - and head unit vendors add
 * their own on top. For example a BYD unit (Freescale MX9TE, KitKat) reports
 * `AudioSystem.getNumStreamTypes() = 11` with `STREAM_MUSIC_SECOND = 10`, a stream AOSP has never
 * had at that index and that a table written against modern Android would label "Accessibility".
 * Getting that wrong is not cosmetic here: on such a unit the extra stream is often the one the
 * amplifier is actually wired to, so it is the one worth offering.
 *
 * Two sources, in order of authority:
 *  1. Reflection on `android.media.AudioSystem` for the `STREAM_*` constants - the only way to
 *     learn a vendor stream's *name*. Non-SDK reflection is restricted from API 28, which is fine:
 *     the ROMs that carry custom streams are old ones where it still works, and everything below
 *     falls back cleanly when it does not.
 *  2. Probing [AudioManager.getStreamMaxVolume], which throws `IllegalArgumentException: Bad
 *     stream type N` for anything the platform does not know. This is public API, works on every
 *     version, and is what decides whether a stream is offered at all.
 */
object AudioStreamCatalog {

    /** One stream the device accepts. */
    data class Entry(
        val id: Int,
        /** Platform constant without the `STREAM_` prefix (e.g. `MUSIC_SECOND`), when reflection found one. */
        val constantName: String?,
        /** True when this id has its documented AOSP meaning on this API level. */
        val isStandard: Boolean
    )

    /**
     * AOSP stream ids and the API level that introduced each. Used to name a stream when
     * reflection is unavailable, and to decide whether an id is standard *here* - the same number
     * is a vendor stream on a platform too old to have defined it.
     */
    private val AOSP_STREAMS = mapOf(
        0 to StandardStream("VOICE_CALL", 1),
        1 to StandardStream("SYSTEM", 1),
        2 to StandardStream("RING", 1),
        3 to StandardStream("MUSIC", 1),
        4 to StandardStream("ALARM", 1),
        5 to StandardStream("NOTIFICATION", 3),
        6 to StandardStream("BLUETOOTH_SCO", 1),
        7 to StandardStream("SYSTEM_ENFORCED", 1),
        8 to StandardStream("DTMF", 5),
        9 to StandardStream("TTS", 1),
        10 to StandardStream("ACCESSIBILITY", Build.VERSION_CODES.O),
        11 to StandardStream("ASSISTANT", 30)
    )

    private data class StandardStream(val name: String, val sinceApi: Int)

    /** How far to probe when reflection could not tell us where the streams stop. */
    private const val BLIND_PROBE_LIMIT = 16

    /** Hard ceiling on the probe, so a device that never throws cannot produce endless entries. */
    private const val MAX_PROBE_LIMIT = 32

    @Volatile
    private var cached: List<Entry>? = null

    /**
     * Every stream this device accepts, by ascending id. Computed once - the set cannot change
     * while the process lives, and the probe costs one binder call per candidate.
     */
    fun available(audioManager: AudioManager): List<Entry> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val discovered = discover(audioManager)
            cached = discovered
            return discovered
        }
    }

    /** True when the device accepts [stream]. */
    fun isAvailable(audioManager: AudioManager, stream: Int): Boolean =
        available(audioManager).any { it.id == stream }

    /**
     * [stream] if the device has it, otherwise [AudioManager.STREAM_MUSIC].
     *
     * Settings travel between devices for example restored from a backup, so a
     * stored id can name a stream this unit has never had. Playing on the media stream is wrong
     * but audible; handing an unknown id to AudioTrack is silence with no message attached.
     */
    fun sanitize(audioManager: AudioManager, stream: Int): Int {
        if (isAvailable(audioManager, stream)) return stream
        AppLog.w("AudioStreamCatalog: stream $stream is not available on this device, " +
                "falling back to STREAM_MUSIC")
        return AudioManager.STREAM_MUSIC
    }

    private fun discover(audioManager: AudioManager): List<Entry> {
        val names = reflectStreamNames()
        val reportedCount = reflectNumStreamTypes()

        // Trust a reported count as the boundary; without one, probe a fixed distance and let the
        // exception mark the end, the way the count would have.
        val limit = when {
            reportedCount != null && reportedCount > 0 ->
                maxOf(reportedCount, (names.keys.maxOrNull() ?: 0) + 1)
            names.isNotEmpty() -> maxOf(BLIND_PROBE_LIMIT, (names.keys.maxOrNull() ?: 0) + 1)
            else -> BLIND_PROBE_LIMIT
        }.coerceAtMost(MAX_PROBE_LIMIT)

        val entries = mutableListOf<Entry>()
        for (id in 0 until limit) {
            val maxVolume = try {
                audioManager.getStreamMaxVolume(id)
            } catch (e: RuntimeException) {
                // IllegalArgumentException on every platform tested ("Bad stream type N"); caught
                // broadly because a vendor framework is free to raise something else.
                continue
            }
            // A device that answers instead of throwing would otherwise fill the picker with
            // streams it cannot play. A reflected constant is proof enough on its own; without
            // one, require the stream to have volume steps.
            if (maxVolume <= 0 && !names.containsKey(id)) continue

            val aosp = AOSP_STREAMS[id]
            val reflected = names[id]
            // Standard only if the platform is new enough to define this id AND the name it
            // reports matches. BYD's MUSIC_SECOND sits at 10, where modern AOSP puts ACCESSIBILITY.
            val isStandard = aosp != null &&
                    Build.VERSION.SDK_INT >= aosp.sinceApi &&
                    (reflected == null || reflected == aosp.name)

            entries.add(Entry(id, reflected ?: aosp?.name.takeIf { isStandard }, isStandard))
        }

        if (entries.isEmpty()) {
            // Nothing answered - keep the app usable rather than showing an empty picker.
            AppLog.w("AudioStreamCatalog: no streams could be probed, assuming the AOSP basics")
            return listOf(
                Entry(AudioManager.STREAM_MUSIC, "MUSIC", true),
                Entry(AudioManager.STREAM_VOICE_CALL, "VOICE_CALL", true),
                Entry(AudioManager.STREAM_NOTIFICATION, "NOTIFICATION", true)
            )
        }

        AppLog.i("AudioStreamCatalog: ${entries.size} streams available " +
                "(reported count ${reportedCount ?: "unknown"}): " +
                entries.joinToString { "${it.id}=${it.constantName ?: "?"}${if (it.isStandard) "" else "*"}" })
        return entries
    }

    /** `STREAM_*` constants from AudioSystem, by value. Empty when reflection is not permitted. */
    private fun reflectStreamNames(): Map<Int, String> {
        return try {
            val cls = Class.forName("android.media.AudioSystem")
            val found = mutableMapOf<Int, String>()
            for (field in cls.declaredFields) {
                if (!field.name.startsWith("STREAM_")) continue
                if (field.type != Int::class.javaPrimitiveType) continue
                field.isAccessible = true
                val value = try {
                    field.getInt(null)
                } catch (e: Exception) {
                    continue
                }
                // STREAM_DEFAULT is -1 and names no stream.
                if (value < 0) continue
                found[value] = field.name.removePrefix("STREAM_")
            }
            found
        } catch (e: Throwable) {
            AppLog.i("AudioStreamCatalog: AudioSystem constants unavailable (${e.javaClass.simpleName}), " +
                    "naming streams from the AOSP table instead")
            emptyMap()
        }
    }

    /** `AudioSystem.getNumStreamTypes()`, or null when reflection is not permitted. */
    private fun reflectNumStreamTypes(): Int? {
        return try {
            val cls = Class.forName("android.media.AudioSystem")
            val method = cls.getMethod("getNumStreamTypes")
            method.isAccessible = true
            (method.invoke(null) as? Int)?.takeIf { it > 0 }
        } catch (e: Throwable) {
            null
        }
    }
}
