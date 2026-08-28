package com.andrerinas.openheadunit.decoder.video

/**
 * The order in which to try optional `MediaFormat` keys when configuring a decoder, and what to fall
 * back to when one is rejected.
 *
 * Every optional key this project ever added was removed again, on the reasoning that a rejected key
 * took the whole session with it, so the only safe number of optional keys was zero. That reasoning
 * was sound and the evidence behind it was not: the `codec does not support config priority
 * (err -1010)` those two keys drew is a line ACodec logs on its way to returning OK. See
 * [realtimeHints].
 *
 * A ladder changes that. Configure is attempted with the richest key set first and, if it throws, the
 * next attempt drops one tier. The last tier is always empty, so the worst case is exactly the
 * behaviour with no optional keys at all - which is what shipped before this existed. That makes any
 * future key cheap to try and impossible to regress on.
 *
 * The keys themselves come from Moonlight's `MediaCodecHelper`, which carries the only device-attributed
 * table of them anyone maintains. The official `KEY_LOW_LATENCY` only exists from API 30, and the five
 * vendor spellings below are what the same feature was called before that - including
 * `vdec-lowlatency`, which MediaTek's fork of ACodec translates to
 * `OMX.MTK.index.param.video.LowLatencyDecode`, and which is the decoder in the 1 GB unit from #839.
 *
 * Nothing here decides *whether* to try the optional tiers; [VideoDecoder] gates that on a setting
 * which is off by default, because this project's rule is that no vendor key ships enabled without a
 * log from a device that accepted it. The ladder's tier log is how that log gets produced.
 *
 * ### A configure that does not throw is not proof the component acted
 *
 * It proves only that the key cost nothing. Reading the format back does not close the gap either:
 * ACodec builds its input format from its own bookkeeping and the queried port definition rather
 * than by re-asking the component per key, so a value can round-trip because ACodec recorded it as
 * set while the component ignored it. Only CCodec reads values genuinely back, and the units this
 * matters on predate it. The evidence is therefore behavioural - the decode latency on the
 * throughput line, measured with a tier on against the same drive with it off.
 */
object DecoderConfigLadder {

    /** One rung: a label for the log and the integer keys to set. */
    data class Tier(val label: String, val integerKeys: Map<String, Int>)

    /** Android 11's official low-latency key. Named here so the string is not repeated. */
    const val KEY_LOW_LATENCY = "low-latency"

    /** API level at which [KEY_LOW_LATENCY] exists. */
    const val LOW_LATENCY_API = 30

    /**
     * MediaTek, via their modified ACodec. Also plumbed on Amazon's Amlogic devices, where per
     * Moonlight it does more than reduce latency - it is what makes their HEVC decoder emit frames at
     * all.
     */
    const val MTK_LOW_LATENCY = "vdec-lowlatency"

    /**
     * MediaTek's output-reorder switch (`OMX_IndexVendorMtkOmxVdecNoReorderMode`) and its
     * motion-interpolation switch, which is a latency *adder* when on.
     *
     * These are the only levers found anywhere that attack the component's own pipeline depth below
     * API 30, where [KEY_LOW_LATENCY] does not exist. They get their own rung above the measured-good
     * one so that a component which dislikes them falls back to [MTK_LOW_LATENCY] alone rather than
     * to nothing.
     */
    const val MTK_NO_REORDER = "vdec-no-record"
    const val MTK_CLEAR_MOTION = "use-clearmotion-mode"

    const val AMLOGIC_LOW_LATENCY = "vendor.low-latency.enable"
    const val QUALCOMM_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
    const val EXYNOS_LOW_LATENCY = "vendor.rtc-ext-dec-low-latency.enable"
    const val HISILICON_LOW_LATENCY_REQ =
        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req"
    const val HISILICON_LOW_LATENCY_RDY =
        "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy"

    /** `MediaFormat.KEY_PRIORITY`, 0 meaning realtime. Spelled out to keep this object pure. */
    const val KEY_PRIORITY = "priority"

    /** `MediaFormat.KEY_OPERATING_RATE`, the rate the component should clock itself for. */
    const val KEY_OPERATING_RATE = "operating-rate"

    /** API level at which [KEY_PRIORITY] and [KEY_OPERATING_RATE] exist. */
    const val REALTIME_HINTS_API = 23

    /** The empty rung. Always last, and always reached, so the fallback is the pre-existing behaviour. */
    val NO_OPTIONAL_KEYS = Tier("none", emptyMap())

    /**
     * Vendor low-latency keys for a decoder, matched on its name, or empty if the family is unknown.
     *
     * Matched by name rather than by `Build` properties because the decoder name is what the key
     * belongs to: a device can carry components from more than one vendor, and it is the component
     * that either understands the key or throws.
     */
    fun vendorLowLatencyKeys(codecName: String): Map<String, Int> {
        val name = codecName.lowercase()
        return when {
            isMediaTek(name) -> mapOf(MTK_LOW_LATENCY to 1)
            name.contains("amlogic") -> mapOf(AMLOGIC_LOW_LATENCY to 1)
            name.contains(".qcom.") || name.contains(".qti.") -> mapOf(QUALCOMM_LOW_LATENCY to 1)
            name.contains("exynos") -> mapOf(EXYNOS_LOW_LATENCY to 1)
            name.contains(".hisi.") || name.contains("hisilicon") ->
                mapOf(HISILICON_LOW_LATENCY_REQ to 1, HISILICON_LOW_LATENCY_RDY to -1)
            else -> emptyMap()
        }
    }

    private fun isMediaTek(lowercaseName: String): Boolean =
        lowercaseName.contains(".mtk.") || lowercaseName.contains("mediatek")

    /**
     * The two scheduler hints, or empty below the API that has them or without a rate to name.
     *
     * These are not vendor keys and they cannot fail a configure. ACodec's `setPriority` and
     * `setOperatingRate` log the component's refusal and then `return OK`, and `configureCodec`
     * adds a second `err = OK` at both call sites, so the `-1010` this project once recorded as a
     * rejection was only ever a log line. They ride on the rungs above rather than getting one of
     * their own, because a rung costs a retry and these cost nothing.
     */
    fun realtimeHints(sdkInt: Int, operatingRate: Int): Map<String, Int> {
        if (sdkInt < REALTIME_HINTS_API) return emptyMap()
        if (operatingRate <= 0) return mapOf(KEY_PRIORITY to 0)
        return mapOf(KEY_PRIORITY to 0, KEY_OPERATING_RATE to operatingRate)
    }

    /**
     * The rungs to try, richest first, always ending in [NO_OPTIONAL_KEYS].
     *
     * [lowLatencyRequested] false returns the single empty rung, which is the shipped behaviour. When
     * it is true, the official key is preferred wherever the component advertises `FEATURE_LowLatency`
     * and the platform is new enough for the key to mean anything; otherwise the vendor spelling for
     * that component is tried. The two are never combined - a component that understands the official
     * key has no need of the older private one, and setting both only widens what a single rejection
     * can take down.
     *
     * A component with no vendor spelling still gets a rung, because [realtimeHints] belongs to no
     * vendor and is all such a component can be offered.
     */
    fun tiers(
        codecName: String,
        sdkInt: Int,
        advertisesLowLatencyFeature: Boolean,
        lowLatencyRequested: Boolean,
        operatingRate: Int = 0,
    ): List<Tier> {
        if (!lowLatencyRequested) return listOf(NO_OPTIONAL_KEYS)

        val hints = realtimeHints(sdkInt, operatingRate)

        if (sdkInt >= LOW_LATENCY_API && advertisesLowLatencyFeature) {
            return listOf(Tier("low-latency", mapOf(KEY_LOW_LATENCY to 1) + hints), NO_OPTIONAL_KEYS)
        }

        val vendorKeys = vendorLowLatencyKeys(codecName)
        if (vendorKeys.isEmpty()) {
            if (hints.isEmpty()) return listOf(NO_OPTIONAL_KEYS)
            return listOf(Tier("realtime", hints), NO_OPTIONAL_KEYS)
        }

        if (isMediaTek(codecName.lowercase())) {
            return listOf(
                Tier(
                    "vendor+reorder",
                    vendorKeys + mapOf(MTK_NO_REORDER to 1, MTK_CLEAR_MOTION to 0) + hints,
                ),
                Tier("vendor", vendorKeys + hints),
                NO_OPTIONAL_KEYS,
            )
        }
        return listOf(Tier("vendor", vendorKeys + hints), NO_OPTIONAL_KEYS)
    }
}
