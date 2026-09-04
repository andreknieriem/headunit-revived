package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokeTargetPolicyTest {

    private val phone = setOf("A0:46:5A:97:E4:95")

    /**
     * The narrowing a user asks for by turning the opt-in off: wake this phone or nothing. Widening
     * stays the default, because the poke is the only thing that starts Android Auto on some units.
     */
    @Test
    fun `no target and no opt-in pokes nothing`() {
        assertEquals(
            PokeTargets.None,
            PokeTargetPolicy.targets(selected = emptySet(), allPairedOptIn = false)
        )
    }

    /** The default, and what an empty list used to do implicitly. */
    @Test
    fun `no target with the opt-in on pokes every paired device`() {
        assertEquals(
            PokeTargets.AllPaired,
            PokeTargetPolicy.targets(selected = emptySet(), allPairedOptIn = true)
        )
    }

    /** A chosen target is used as chosen, and the opt-in never widens it. */
    @Test
    fun `a chosen target is never widened`() {
        assertEquals(
            PokeTargets.Selected(phone),
            PokeTargetPolicy.targets(selected = phone, allPairedOptIn = true)
        )
        assertEquals(
            PokeTargets.Selected(phone),
            PokeTargetPolicy.targets(selected = phone, allPairedOptIn = false)
        )
    }

    /**
     * The field failure: a completed handshake wrote its peer into the list that also gated
     * Bluetooth auto-start, so clearing auto-start undid itself. It fills the poke target only,
     * and only when there is none.
     */
    @Test
    fun `a handshaked device is adopted only when nothing is chosen`() {
        assertTrue(PokeTargetPolicy.adoptsHandshakedDevice(emptySet()))
        assertFalse(PokeTargetPolicy.adoptsHandshakedDevice(phone))
    }
}
