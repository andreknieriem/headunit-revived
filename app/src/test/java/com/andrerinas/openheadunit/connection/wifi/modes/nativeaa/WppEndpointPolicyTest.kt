package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WppEndpointPolicyTest {

    @Test
    fun `wifi direct withholds even while the server is listening`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `hotspot advertises the port the server is on`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, 5299)
        assertEquals(WppEndpointDecision.Advertise(5299), decision)
    }

    @Test
    fun `hotspot withholds when nothing is listening`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, null)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `the two refusals say different things and both say something`() {
        val transport = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299)
        val notListening = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, null)
        val transportReason = (transport as WppEndpointDecision.Withhold).reason
        val notListeningReason = (notListening as WppEndpointDecision.Withhold).reason
        assertTrue(transportReason.isNotBlank())
        assertTrue(notListeningReason.isNotBlank())
        assertTrue(transportReason != notListeningReason)
    }

    @Test
    fun `the transport refusal says how to clear an endpoint the phone already holds`() {
        // Measured: withholding stops us creating a record and does nothing to one Android Auto is
        // already carrying from a hotspot session, which it dials until it is forgotten.
        val reason = (WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299)
            as WppEndpointDecision.Withhold).reason
        assertTrue(reason.contains("forget this head unit on the phone"))
    }

    @Test
    fun `a port the server reports is advertised verbatim, not the default`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, 41234)
        assertEquals(41234, (decision as WppEndpointDecision.Advertise).port)
    }
}
