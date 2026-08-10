package com.andrerinas.openheadunit.aap

import java.util.UUID

/**
 * Rules for the Native AA wake poke: which of the phone's Bluetooth records it may reach for, when
 * it may connect at all, and which stored MACs are worth keeping.
 *
 * The poke raises an ACL connection so the phone notices a wireless-capable head unit. It is
 * load-bearing — some units never connect without it — so nothing here switches it off. What the
 * rules decide is when a poke would cost more than it gains.
 *
 * Pure and unit-tested; the sockets live in `NativeAaHandshakeManager`.
 */
object BluetoothWakePolicy {

    /** Handsfree Profile — Audio Gateway. The phone's hands-free side; a call rides on this. */
    val HFP_AG_UUID: UUID = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb")

    /**
     * Headset Profile — Audio Gateway. Long stored under the name `A2DP_SOURCE_UUID`, which it never
     * was: A2DP Source is `0000110a`. Both values are confirmed against
     * nisargjhaveri/WirelessAndroidAutoDongle and mossyhub/openautolink.
     */
    val HSP_AG_UUID: UUID = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb")

    /**
     * The records a poke tries, in order — openautolink's ConnectProfile fallback chain.
     *
     * Deliberately not a setting. A selectable HSP-AG-only mode was built and removed once the rig
     * measured a successful HSP-AG poke leaving this unit's hands-free link down for three minutes,
     * the same outcome as HFP-AG: a phone serves both records from one headset connection, so which
     * one is asked for was never the lever. [shouldPoke] is.
     */
    val POKE_TARGETS: List<UUID> = listOf(HFP_AG_UUID, HSP_AG_UUID)

    /** Reader-facing name for a target, so a log says what was touched rather than a UUID. */
    fun profileName(uuid: UUID): String = when (uuid) {
        HFP_AG_UUID -> "HFP-AG"
        HSP_AG_UUID -> "HSP-AG"
        else -> uuid.toString()
    }

    /** What this head unit's own Bluetooth stack says about its hands-free link. */
    enum class HandsFreeLink {
        /** A link is up. Poking would take the phone's slot away from it. */
        CONNECTED,

        /** No link. Nothing for a poke to displace. */
        ABSENT,

        /** The adapter would not say. Pokes anyway — see [shouldPoke]. */
        UNREADABLE;

        companion object {
            /** Maps [com.andrerinas.openheadunit.utils.BluetoothHelper.handsFreeLinkState]'s
             *  three-valued answer, so the null case is named rather than implied. */
            fun of(connected: Boolean?): HandsFreeLink = when (connected) {
                true -> CONNECTED
                false -> ABSENT
                null -> UNREADABLE
            }
        }
    }

    /**
     * Whether the wake poke may run, given what this head unit's own hands-free link is doing.
     *
     * Measured, not theorised: a poke that connects takes the phone's single hands-free slot and
     * this unit's own client is dropped to make room. `HfpClientConnectionService` logged its
     * disconnect from the same peer 4 ms after `socket.connect()` returned, and the link stayed down
     * eight minutes until a Bluetooth adapter cycle. The user sees a head unit reporting Bluetooth
     * disconnected while the phone reports it connected, and calls coming out of the phone.
     *
     * Skipping costs nothing, because a live hands-free link *is* the ACL connection a poke exists
     * to create. Units where the poke is load-bearing have no such link to read, so they keep
     * today's behaviour — as does [HandsFreeLink.UNREADABLE], since an adapter that will not report
     * its profiles must not silently disable a mechanism some units cannot connect without.
     */
    fun shouldPoke(handsFreeLink: HandsFreeLink): Boolean = handsFreeLink != HandsFreeLink.CONNECTED

    /** What a stored Auto Start MAC's pairing state read as, when the poke went looking for it. */
    enum class BondReading {
        /** Paired. Safe to poke. */
        BONDED,

        /** A positive not-paired answer, taken from a working adapter. */
        NOT_BONDED,

        /** Not a Bluetooth address at all, so it can never become valid. */
        MALFORMED,

        /** The adapter is off, or would not answer. Says nothing about the device. */
        UNREADABLE
    }

    /**
     * Whether a poke may open a socket to a device in this state.
     *
     * Only a confirmed pairing. A poke is a raw RFCOMM `connect()` to a device we assume already
     * trusts us; against an unpaired one the OS starts a new pairing negotiation as a side effect,
     * which the user sees as the head unit asking to pair every time the phone's radio comes back.
     *
     * Strict where [shouldForget] is lenient: refusing to poke costs a retry seconds later.
     */
    fun mayPoke(reading: BondReading): Boolean = reading == BondReading.BONDED

    /**
     * Whether a stored MAC should be dropped from the Auto Start list.
     *
     * Only on evidence the device is genuinely gone. **Never on [BondReading.UNREADABLE]** —
     * `getBondState()` answers "not bonded" when the Bluetooth service is merely unavailable, so
     * treating that as unpaired would delete the user's configured device on any poke round that
     * landed while the adapter was off. This head unit's Bluetooth is known to cycle itself, so that
     * window is reachable, and the deletion is written straight through to device-protected storage
     * where nothing would restore it.
     *
     * Lenient where [mayPoke] is strict: a MAC kept one round too long costs nothing, because
     * [mayPoke] will not poke it.
     */
    fun shouldForget(reading: BondReading): Boolean =
        reading == BondReading.NOT_BONDED || reading == BondReading.MALFORMED
}
