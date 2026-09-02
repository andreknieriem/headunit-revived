package com.andrerinas.openheadunit.connection.wifi.direct

/** Whether the network the phone is handed will be the same network next time. */
enum class GroupIdentityStability {
    /** Same name and same BSSID as the last group this unit hosted, or an address the user fixed. */
    STABLE,
    /** Nothing to compare against yet, or nothing readable to compare. */
    UNPROVEN,
    /** Same name, different BSSID: this unit re-addresses the group on every create. */
    CHANGED,
    /** Not this transport's question: an access point's identity is its own. */
    NOT_MEASURED,
}

/** What a group looked like from the outside, kept so the next one can be compared to it. */
data class ObservedP2pGroup(val ssid: String, val bssid: String)

/**
 * Decides, per unit and from evidence, whether the WiFi Direct group's identity repeats.
 *
 * A persistent group keeps its name and passphrase, but the platform is allowed to re-randomize
 * the group's own address on every create, and whether it does is a per-unit configuration this
 * app cannot read. The phone stores the BSSID beside the name and joins on both, so a record that
 * names a stale address is a network it can never find. The verdict is therefore measured, from
 * two consecutive bring-ups, and never assumed.
 */
object GroupIdentityStabilityPolicy {

    data class Verdict(
        val stability: GroupIdentityStability,
        /** What to keep for the next comparison, or null when this bring-up taught nothing. */
        val remember: ObservedP2pGroup?,
        val reason: String,
    )

    fun assess(
        keepIdentity: Boolean,
        requestedName: String?,
        ssid: String,
        bssid: String,
        bssidUsable: Boolean,
        staticOverride: Boolean,
        previous: ObservedP2pGroup?,
    ): Verdict {
        if (!keepIdentity) {
            return Verdict(GroupIdentityStability.UNPROVEN, null, "a new network is made on every create")
        }
        if (requestedName != null && requestedName != ssid) {
            return Verdict(
                GroupIdentityStability.UNPROVEN, null,
                "the platform named the group $ssid instead of the $requestedName asked for",
            )
        }
        if (!bssidUsable) {
            return Verdict(GroupIdentityStability.UNPROVEN, null, "no BSSID could be read for this group")
        }
        val observed = ObservedP2pGroup(ssid, bssid)
        if (staticOverride) {
            return Verdict(
                GroupIdentityStability.STABLE, observed,
                "the static BSSID setting fixes the address the phone is told",
            )
        }
        return when {
            previous == null -> Verdict(
                GroupIdentityStability.UNPROVEN, observed,
                "first group under this name; the next one decides",
            )
            previous.ssid != ssid -> Verdict(
                GroupIdentityStability.UNPROVEN, observed,
                "the name changed since the last group (${previous.ssid}), so the address cannot be compared yet",
            )
            previous.bssid == bssid -> Verdict(
                GroupIdentityStability.STABLE, observed,
                "same name and same BSSID as the last group",
            )
            else -> Verdict(
                GroupIdentityStability.CHANGED, observed,
                "same name but the BSSID moved from ${previous.bssid} to $bssid; this unit re-addresses the group on every create",
            )
        }
    }

    fun label(stability: GroupIdentityStability): String = when (stability) {
        GroupIdentityStability.STABLE -> "yes"
        GroupIdentityStability.UNPROVEN -> "unproven"
        GroupIdentityStability.CHANGED -> "no"
        GroupIdentityStability.NOT_MEASURED -> "not measured"
    }
}
