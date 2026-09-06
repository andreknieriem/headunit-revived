package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Which regulatory domain this unit's WiFi is operating under, from whatever source will say.
 *
 * The domain decides which 5 GHz channels a group owner may transmit on, and a channel the driver
 * refuses is indistinguishable in a log from a channel the app asked for wrongly. Naming the country
 * separates the two, and it is the only way to tell a reporter whose phone is in a different domain
 * that the two do not overlap.
 *
 * Pure, so every source combination is a unit test rather than a device.
 */
object WifiCountryPolicy {

    /** What the framework and the drivers use when no country has been established. */
    private val WORLD_DOMAINS = setOf("00", "WW")

    /**
     * A source's answer as a country, or null where it did not give one.
     *
     * The world domain is deliberately null: it is the *absence* of a country, and reporting it as
     * one would hide the case that most often explains a refusal. [isWorldDomain] is how a caller
     * tells that apart from a source that simply said nothing.
     */
    fun normalise(raw: String?): String? {
        val trimmed = raw?.trim()?.uppercase() ?: return null
        if (trimmed in WORLD_DOMAINS) return null
        if (trimmed.length != 2 || !trimmed.all { it in 'A'..'Z' }) return null
        return trimmed
    }

    /** True for an answer that names the world domain rather than a country. */
    fun isWorldDomain(raw: String?): Boolean = raw?.trim()?.uppercase() in WORLD_DOMAINS

    /**
     * The first source that names a country, or null when none does.
     *
     * Order is the caller's, and it is meant to be AOSP's own: `WifiCountryCode.pickCountryCode()`
     * takes telephony before the baked-in default, so a log that ranks sources the same way can be
     * read against what the framework actually did.
     */
    fun choose(sources: Map<String, String?>): String? =
        sources.values.firstNotNullOfOrNull { normalise(it) }

    /**
     * How the domain reads in the line that reports a refused 5 GHz channel.
     *
     * Named rather than silent when nothing answered: an unset domain is itself the finding, because
     * a driver with no country will not start a group owner on channels that need one.
     */
    fun describe(sources: Map<String, String?>): String {
        val country = choose(sources)
        if (country != null) return "regulatory domain $country"
        if (sources.values.any { isWorldDomain(it) }) {
            return "no regulatory domain set on this unit (world domain)"
        }
        return "regulatory domain unknown, nothing on this unit would say"
    }
}
