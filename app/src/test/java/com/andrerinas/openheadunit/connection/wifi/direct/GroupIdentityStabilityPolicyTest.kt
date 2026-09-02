package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupIdentityStabilityPolicyTest {

    private val name = "DIRECT-K7-HeadUnit"
    private val a = "DE:B3:88:55:B3:92"
    private val b = "DE:B3:88:55:B3:93"

    private fun assess(
        keep: Boolean = true,
        requested: String? = name,
        ssid: String = name,
        bssid: String = a,
        usable: Boolean = true,
        override: Boolean = false,
        previous: ObservedP2pGroup? = null,
    ) = GroupIdentityStabilityPolicy.assess(keep, requested, ssid, bssid, usable, override, previous)

    @Test
    fun `the first group under a name is unproven and is remembered`() {
        val v = assess()
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertEquals(ObservedP2pGroup(name, a), v.remember)
    }

    @Test
    fun `the same name and address twice is stable`() {
        val v = assess(previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.STABLE, v.stability)
        assertEquals(ObservedP2pGroup(name, a), v.remember)
    }

    @Test
    fun `the same name with a moved address is changed, and the new address is what is kept`() {
        val v = assess(bssid = b, previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.CHANGED, v.stability)
        assertEquals(ObservedP2pGroup(name, b), v.remember)
        assertTrue(v.reason, v.reason.contains(a) && v.reason.contains(b))
    }

    @Test
    fun `a unit that re-addresses every create never reaches stable`() {
        var previous: ObservedP2pGroup? = null
        val seen = mutableListOf<GroupIdentityStability>()
        for (mac in listOf(a, b, a, b)) {
            val v = assess(bssid = mac, previous = previous)
            seen += v.stability
            previous = v.remember
        }
        assertEquals(
            listOf(GroupIdentityStability.UNPROVEN, GroupIdentityStability.CHANGED,
                GroupIdentityStability.CHANGED, GroupIdentityStability.CHANGED),
            seen,
        )
    }

    @Test
    fun `a new identity resets the comparison rather than reading as changed`() {
        val v = assess(ssid = "DIRECT-Q2-HeadUnit", requested = "DIRECT-Q2-HeadUnit", previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertEquals(ObservedP2pGroup("DIRECT-Q2-HeadUnit", a), v.remember)
    }

    @Test
    fun `a static BSSID setting is stable at once`() {
        val v = assess(override = true)
        assertEquals(GroupIdentityStability.STABLE, v.stability)
    }

    @Test
    fun `not keeping the identity is never stable and teaches nothing`() {
        val v = assess(keep = false, previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertNull(v.remember)
    }

    @Test
    fun `a group the platform renamed cannot be the one the phone stored`() {
        val v = assess(ssid = "DIRECT-zz-Android", previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertNull(v.remember)
        assertTrue(v.reason.contains(name))
    }

    @Test
    fun `an unreadable BSSID is unproven and does not overwrite what was learned`() {
        val v = assess(bssid = "02:00:00:00:00:00", usable = false, previous = ObservedP2pGroup(name, a))
        assertEquals(GroupIdentityStability.UNPROVEN, v.stability)
        assertNull(v.remember)
    }

    @Test
    fun `every verdict carries a reason and every stability a label`() {
        for (v in listOf(assess(), assess(keep = false), assess(override = true),
            assess(previous = ObservedP2pGroup(name, a)), assess(bssid = b, previous = ObservedP2pGroup(name, a)))) {
            assertTrue(v.reason.isNotBlank())
        }
        for (s in GroupIdentityStability.values()) {
            assertTrue(GroupIdentityStabilityPolicy.label(s).isNotBlank())
        }
    }
}
