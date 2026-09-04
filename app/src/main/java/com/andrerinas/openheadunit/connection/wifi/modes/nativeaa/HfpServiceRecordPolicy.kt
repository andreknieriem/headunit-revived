package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * How far this app stands in for hands-free: whether to publish the stand-in record, and whether to
 * open the link on it.
 *
 * The record exists only so a phone sees a hands-free profile while deciding whether this is a head
 * unit, and it cannot carry call audio: the responder negotiates neither a codec nor a SCO link.
 * Where the device already advertises Hands-Free, the real stack is there to answer, and adding a
 * second record beside it can win the phone's connection and end calls in an app that cannot serve
 * them.
 *
 * Pure: UUIDs as strings, so no ParcelUuid and no adapter.
 */
object HfpServiceRecordPolicy {

    /** Hands-Free, the head unit's half of HFP. Phones advertise Audio Gateway (`0000111f`). */
    const val HANDS_FREE_UUID = "0000111e-0000-1000-8000-00805f9b34fb"

    /**
     * Whether to register it. A null [localUuids] means the adapter could not be asked, and a
     * question that could not be asked is not a question answered yes: register, as before.
     */
    fun shouldRegisterDummyHfp(localUuids: List<String>?): Boolean {
        if (localUuids == null) return true
        return localUuids.none { it.equals(HANDS_FREE_UUID, ignoreCase = true) }
    }

    /**
     * Whether to open the service level connection on a stand-in record, rather than only answering
     * on it.
     *
     * A completed link means the phone routes calls here and this app cannot play them, so a real
     * hands-free device goes first: a readable, positive [handsFreeLink] stands the stand-in down.
     * Only that stands it down, matching [shouldRegisterDummyHfp] and
     * [BluetoothWakePolicy.shouldPoke], because a question that could not be asked is not a question
     * answered yes.
     *
     * Asked once, when a socket is about to be spoken on, and never re-asked. Dropping a link the
     * phone has already accepted is what it reads as the head unit's Bluetooth disappearing, after
     * which it stops retrying wireless setup entirely.
     */
    fun shouldOpenServiceLevelConnection(
        enabled: Boolean,
        publishedStandIn: Boolean,
        handsFreeLink: BluetoothWakePolicy.HandsFreeLink,
    ): Boolean =
        enabled && publishedStandIn && handsFreeLink != BluetoothWakePolicy.HandsFreeLink.CONNECTED
}
