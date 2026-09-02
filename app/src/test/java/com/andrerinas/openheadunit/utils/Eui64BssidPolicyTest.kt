package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBssidPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Eui64BssidPolicyTest {

    /** An IPv6 address written as 32 hex characters, no separators. */
    private fun addr(hex: String): ByteArray {
        require(hex.length == 32) { "expected 32 hex characters, got ${hex.length}" }
        return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // fe80::021a:11ff:fef1:9e5c
    private val universalBitSet = addr("fe80000000000000021a11fffef19e5c")

    // fe80::001a:11ff:fef1:9e5c
    private val universalBitClear = addr("fe80000000000000001a11fffef19e5c")

    // fe80::1234:5678:9abc:def0 - no ff:fe marker, i.e. RFC 7217 stable privacy
    private val stablePrivacy = addr("fe80000000000000123456789abcdef0")

    @Test
    fun `derives the mac and undoes the universal-local flip`() {
        assertEquals("00:1A:11:F1:9E:5C", Eui64BssidPolicy.fromLinkLocal(universalBitSet))
    }

    @Test
    fun `the flip goes both ways`() {
        assertEquals("02:1A:11:F1:9E:5C", Eui64BssidPolicy.fromLinkLocal(universalBitClear))
    }

    @Test
    fun `an address with no ff fe marker yields nothing`() {
        assertNull(Eui64BssidPolicy.fromLinkLocal(stablePrivacy))
    }

    @Test
    fun `only the ff fe marker is accepted, not a near miss`() {
        // ff:ff and fe:fe at bytes 11 and 12 - both one byte away from the marker.
        assertNull(Eui64BssidPolicy.fromLinkLocal(addr("fe80000000000000021a11fffff19e5c")))
        assertNull(Eui64BssidPolicy.fromLinkLocal(addr("fe80000000000000021a11fefef19e5c")))
    }

    @Test
    fun `a wrong-length or absent address yields nothing`() {
        assertNull(Eui64BssidPolicy.fromLinkLocal(null))
        assertNull(Eui64BssidPolicy.fromLinkLocal(ByteArray(4)))
        assertNull(Eui64BssidPolicy.fromLinkLocal(ByteArray(0)))
    }

    @Test
    fun `every link-local is tried, not only the first`() {
        val macs = listOf(stablePrivacy, universalBitSet)
        assertEquals("00:1A:11:F1:9E:5C", Eui64BssidPolicy.fromLinkLocals(macs))
    }

    @Test
    fun `no link-local yields anything`() {
        assertNull(Eui64BssidPolicy.fromLinkLocals(emptyList()))
        assertNull(Eui64BssidPolicy.fromLinkLocals(listOf(stablePrivacy)))
    }

    @Test
    fun `access point and p2p interfaces are accepted`() {
        listOf("p2p-wlan0-0", "p2p0", "ap0", "swlan0", "wlan1").forEach {
            assertTrue(it, Eui64BssidPolicy.looksLikeApOrP2p(it))
        }
    }

    @Test
    fun `the station interface and unrelated ones are rejected`() {
        // wlan0 is the whole point: its MAC describes the network this device has joined.
        listOf("wlan0", "wlan2", "lo", "rmnet0", "seth_lte0", "dummy0", "", null).forEach {
            assertFalse(it ?: "null", Eui64BssidPolicy.looksLikeApOrP2p(it))
        }
    }

    @Test
    fun `the named interface is preferred over any other match`() {
        val candidates = listOf(
            Eui64BssidPolicy.Candidate("ap0", listOf(universalBitClear)),
            Eui64BssidPolicy.Candidate("p2p-wlan0-0", listOf(universalBitSet))
        )
        assertEquals(
            Eui64BssidPolicy.Match("p2p-wlan0-0", "00:1A:11:F1:9E:5C"),
            Eui64BssidPolicy.choose(candidates, "p2p-wlan0-0")
        )
    }

    @Test
    fun `a named interface with no usable address falls through to the name filter`() {
        val candidates = listOf(
            Eui64BssidPolicy.Candidate("p2p-wlan0-0", listOf(stablePrivacy)),
            Eui64BssidPolicy.Candidate("ap0", listOf(universalBitSet))
        )
        assertEquals(
            Eui64BssidPolicy.Match("ap0", "00:1A:11:F1:9E:5C"),
            Eui64BssidPolicy.choose(candidates, "p2p-wlan0-0")
        )
    }

    @Test
    fun `the station interface is never chosen even when it is the only one with an address`() {
        val candidates = listOf(Eui64BssidPolicy.Candidate("wlan0", listOf(universalBitSet)))
        assertNull(Eui64BssidPolicy.choose(candidates, null))
    }

    @Test
    fun `an unknown preferred name still allows a filtered match`() {
        val candidates = listOf(Eui64BssidPolicy.Candidate("p2p0", listOf(universalBitSet)))
        assertEquals(
            Eui64BssidPolicy.Match("p2p0", "00:1A:11:F1:9E:5C"),
            Eui64BssidPolicy.choose(candidates, "p2p-wlan0-9")
        )
    }

    @Test
    fun `nothing anywhere yields no match`() {
        assertNull(Eui64BssidPolicy.choose(emptyList(), "p2p-wlan0-0"))
    }

    @Test
    fun `the derived address is one the credentials policy will accept and not renormalise`() {
        val mac = Eui64BssidPolicy.fromLinkLocal(universalBitSet)!!
        assertTrue(SoftApBssidPolicy.isUsable(mac))
        assertEquals(mac, SoftApBssidPolicy.choose(null, mac, null))
    }
}
