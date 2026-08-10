package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothWakePolicyTest {

    // --- the targets themselves, pinned ---

    @Test
    fun `the poke targets are the assigned audio gateway service classes`() {
        assertEquals("0000111f-0000-1000-8000-00805f9b34fb", BluetoothWakePolicy.HFP_AG_UUID.toString())
        assertEquals("00001112-0000-1000-8000-00805f9b34fb", BluetoothWakePolicy.HSP_AG_UUID.toString())
    }

    /**
     * The poke is load-bearing on some head units — they never connect without it — so the list may
     * never be emptied to disable it. Standing a poke down is [BluetoothWakePolicy.shouldPoke]'s
     * decision, taken per attempt, and not this list's.
     */
    @Test
    fun `hands-free is tried first, headset second, and neither is ever dropped`() {
        assertEquals(
            listOf(BluetoothWakePolicy.HFP_AG_UUID, BluetoothWakePolicy.HSP_AG_UUID),
            BluetoothWakePolicy.POKE_TARGETS
        )
    }

    @Test
    fun `targets are named for a log reader`() {
        assertEquals("HFP-AG", BluetoothWakePolicy.profileName(BluetoothWakePolicy.HFP_AG_UUID))
        assertEquals("HSP-AG", BluetoothWakePolicy.profileName(BluetoothWakePolicy.HSP_AG_UUID))
    }

    @Test
    fun `an unknown uuid still prints as itself`() {
        val other = java.util.UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        assertEquals(other.toString(), BluetoothWakePolicy.profileName(other))
    }

    // --- the guard: never poke a link we would destroy ---

    /**
     * The measured failure: a poke that connects takes the phone's one hands-free slot, this unit's
     * own client is dropped 4 ms later, and it does not come back without a Bluetooth adapter cycle.
     */
    @Test
    fun `a live hands-free link is never poked`() {
        assertFalse(BluetoothWakePolicy.shouldPoke(BluetoothWakePolicy.HandsFreeLink.CONNECTED))
    }

    @Test
    fun `no hands-free link means there is nothing to destroy, so poke`() {
        assertTrue(BluetoothWakePolicy.shouldPoke(BluetoothWakePolicy.HandsFreeLink.ABSENT))
    }

    /**
     * An adapter that will not report its profiles must not silently disable a mechanism some head
     * units cannot connect without. Those units keep today's behaviour.
     */
    @Test
    fun `an unreadable adapter still pokes`() {
        assertTrue(BluetoothWakePolicy.shouldPoke(BluetoothWakePolicy.HandsFreeLink.UNREADABLE))
    }

    @Test
    fun `the three-valued profile read maps onto the three states`() {
        assertEquals(BluetoothWakePolicy.HandsFreeLink.CONNECTED, BluetoothWakePolicy.HandsFreeLink.of(true))
        assertEquals(BluetoothWakePolicy.HandsFreeLink.ABSENT, BluetoothWakePolicy.HandsFreeLink.of(false))
        assertEquals(BluetoothWakePolicy.HandsFreeLink.UNREADABLE, BluetoothWakePolicy.HandsFreeLink.of(null))
    }

    /** Exactly one state suppresses the poke; if a fourth is ever added it has to choose out loud. */
    @Test
    fun `only a connected link suppresses the poke`() {
        val suppressed = BluetoothWakePolicy.HandsFreeLink.values().filterNot { BluetoothWakePolicy.shouldPoke(it) }
        assertEquals(listOf(BluetoothWakePolicy.HandsFreeLink.CONNECTED), suppressed)
    }

    // --- pairing: strict about poking, lenient about forgetting ---

    private val BONDED = BluetoothWakePolicy.BondReading.BONDED
    private val NOT_BONDED = BluetoothWakePolicy.BondReading.NOT_BONDED
    private val MALFORMED = BluetoothWakePolicy.BondReading.MALFORMED
    private val UNREADABLE = BluetoothWakePolicy.BondReading.UNREADABLE

    @Test
    fun `only a confirmed pairing may be poked`() {
        assertTrue(BluetoothWakePolicy.mayPoke(BONDED))
        for (reading in BluetoothWakePolicy.BondReading.values().filterNot { it == BONDED }) {
            assertFalse("$reading should not be poked", BluetoothWakePolicy.mayPoke(reading))
        }
    }

    /**
     * The one that matters: `getBondState()` answers BOND_NONE when the Bluetooth service is
     * unavailable, so an adapter that is off looks exactly like a phone the user unpaired. Forgetting
     * is permanent and written through to device-protected storage, so it needs a real answer.
     */
    @Test
    fun `an unreadable state is never forgotten`() {
        assertFalse(BluetoothWakePolicy.shouldForget(UNREADABLE))
    }

    @Test
    fun `a paired device is never forgotten`() {
        assertFalse(BluetoothWakePolicy.shouldForget(BONDED))
    }

    @Test
    fun `a positive not-paired answer is forgotten`() {
        assertTrue(BluetoothWakePolicy.shouldForget(NOT_BONDED))
    }

    /** An address that is not a Bluetooth address can never become one. */
    @Test
    fun `a malformed address is forgotten`() {
        assertTrue(BluetoothWakePolicy.shouldForget(MALFORMED))
    }

    /**
     * The asymmetry is the design: refusing to poke costs a retry seconds later, forgetting costs
     * the user their configured device with nothing to restore it. So nothing may ever be forgotten
     * that was also considered pokeable, and the two rules must never both be lenient.
     */
    @Test
    fun `nothing pokeable is ever forgotten`() {
        for (reading in BluetoothWakePolicy.BondReading.values()) {
            assertFalse(
                "$reading was both poked and forgotten",
                BluetoothWakePolicy.mayPoke(reading) && BluetoothWakePolicy.shouldForget(reading)
            )
        }
    }
}
