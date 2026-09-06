package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.PokeOverlapPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokeOverlapPolicyTest {

    private val phone = "A0:46:5A:97:E4:95"
    private val otherPhone = "B1:57:6B:A8:F5:06"

    @Test
    fun `nothing connecting, so the poke goes straight out`() {
        assertEquals(Step.PROCEED, PokeOverlapPolicy.step(connectingTo = null, target = phone, waitedMs = 0))
    }

    @Test
    fun `a connect to the same phone is waited for`() {
        assertEquals(Step.WAIT, PokeOverlapPolicy.step(connectingTo = phone, target = phone, waitedMs = 0))
    }

    @Test
    fun `case in an address does not decide it`() {
        assertEquals(
            Step.WAIT,
            PokeOverlapPolicy.step(connectingTo = phone.lowercase(), target = phone, waitedMs = 0)
        )
    }

    @Test
    fun `a connect to another phone shares no channel, so it is not waited for`() {
        assertEquals(
            Step.PROCEED,
            PokeOverlapPolicy.step(connectingTo = otherPhone, target = phone, waitedMs = 0)
        )
    }

    @Test
    fun `an unknown target is not matched against anything`() {
        assertEquals(Step.PROCEED, PokeOverlapPolicy.step(connectingTo = phone, target = "", waitedMs = 0))
    }

    @Test
    fun `the wait is bounded, so a stuck connect cannot hold the poke forever`() {
        assertEquals(
            Step.WAIT,
            PokeOverlapPolicy.step(
                connectingTo = phone,
                target = phone,
                waitedMs = PokeOverlapPolicy.CONNECT_SETTLE_WAIT_MS - 1
            )
        )
    }

    @Test
    fun `a connect still in flight after the whole wait is abandoned, not raced`() {
        assertEquals(
            Step.ABANDON,
            PokeOverlapPolicy.step(
                connectingTo = phone,
                target = phone,
                waitedMs = PokeOverlapPolicy.CONNECT_SETTLE_WAIT_MS
            )
        )
        assertEquals(
            Step.ABANDON,
            PokeOverlapPolicy.step(
                connectingTo = phone,
                target = phone,
                waitedMs = PokeOverlapPolicy.CONNECT_SETTLE_WAIT_MS * 10
            )
        )
    }

    @Test
    fun `only the same phone is ever abandoned for`() {
        assertEquals(
            Step.PROCEED,
            PokeOverlapPolicy.step(
                connectingTo = otherPhone,
                target = phone,
                waitedMs = PokeOverlapPolicy.CONNECT_SETTLE_WAIT_MS
            )
        )
        assertEquals(
            Step.PROCEED,
            PokeOverlapPolicy.step(
                connectingTo = null,
                target = phone,
                waitedMs = PokeOverlapPolicy.CONNECT_SETTLE_WAIT_MS
            )
        )
    }

    @Test
    fun `the bound outlasts a connect to a phone whose Bluetooth is off`() {
        // That connect runs the full RFCOMM timeout: 15.40-15.46s, measured three times on the rig.
        assertTrue(PokeOverlapPolicy.CONNECT_SETTLE_WAIT_MS > 15_460L)
        assertTrue(PokeOverlapPolicy.POLL_MS in 1..PokeOverlapPolicy.CONNECT_SETTLE_WAIT_MS)
    }
}
