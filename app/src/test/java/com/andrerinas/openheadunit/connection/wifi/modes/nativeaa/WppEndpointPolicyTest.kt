package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WppEndpointPolicyTest {

    @Test
    fun `wifi direct withholds while its identity is unproven, even with the server listening`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.UNPROVEN)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `wifi direct withholds on a unit that re-addresses the group every create`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.CHANGED)
        assertTrue(decision is WppEndpointDecision.Withhold)
        assertTrue((decision as WppEndpointDecision.Withhold).reason.contains("new address on every create"))
    }

    @Test
    fun `wifi direct advertises once the identity has repeated`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.STABLE)
        assertEquals(WppEndpointDecision.Advertise(5299), decision)
    }

    @Test
    fun `a not-measured verdict on wifi direct is not a pass`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.NOT_MEASURED)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `hotspot advertises the port the server is on, whatever the verdict says`() {
        for (identity in GroupIdentityStability.values()) {
            val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, 5299, identity)
            assertEquals(identity.name, WppEndpointDecision.Advertise(5299), decision)
        }
    }

    @Test
    fun `hotspot withholds when nothing is listening`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, null, GroupIdentityStability.NOT_MEASURED)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `a stable wifi direct group with nothing listening still withholds`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, null, GroupIdentityStability.STABLE)
        assertTrue(decision is WppEndpointDecision.Withhold)
    }

    @Test
    fun `the refusals say different things and each says something`() {
        val unproven = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.UNPROVEN)
        val changed = WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, GroupIdentityStability.CHANGED)
        val notListening = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, null, GroupIdentityStability.NOT_MEASURED)
        val reasons = listOf(unproven, changed, notListening).map { (it as WppEndpointDecision.Withhold).reason }
        assertTrue(reasons.all { it.isNotBlank() })
        assertEquals(3, reasons.toSet().size)
    }

    @Test
    fun `every identity refusal says how to clear an endpoint the phone already holds`() {
        // Measured: withholding stops us creating a record and does nothing to one Android Auto is
        // already carrying, which it dials until it is forgotten.
        for (identity in listOf(GroupIdentityStability.UNPROVEN, GroupIdentityStability.CHANGED)) {
            val reason = (WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, identity)
                as WppEndpointDecision.Withhold).reason
            assertTrue(identity.name, reason.contains("forget this head unit on the phone"))
        }
    }

    @Test
    fun `a port the server reports is advertised verbatim, not the default`() {
        val decision = WppEndpointPolicy.decide(NativeStrategy.HOTSPOT, 41234, GroupIdentityStability.NOT_MEASURED)
        assertEquals(41234, (decision as WppEndpointDecision.Advertise).port)
    }
}
