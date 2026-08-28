package com.andrerinas.openheadunit.connection.projection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackBindPolicyTest {

    @Test
    fun `the head unit server address Self Mode dials needs no binding`() {
        assertFalse(LoopbackBindPolicy.needsNetworkBinding("127.0.0.1"))
    }

    @Test
    fun `the whole of 127 slash 8 is loopback, not just the one address`() {
        // A prefix test on "127.0.0." and a real /8 test differ here, and both of these reach the
        // same local stack.
        assertFalse(LoopbackBindPolicy.needsNetworkBinding("127.1.2.3"))
        assertFalse(LoopbackBindPolicy.needsNetworkBinding("127.255.255.254"))
    }

    @Test
    fun `the names and IPv6 spellings of loopback count too`() {
        assertFalse(LoopbackBindPolicy.needsNetworkBinding("localhost"))
        assertFalse(LoopbackBindPolicy.needsNetworkBinding("LocalHost"))
        assertFalse(LoopbackBindPolicy.needsNetworkBinding(" 127.0.0.1 "))
        assertFalse(LoopbackBindPolicy.needsNetworkBinding("::1"))
        assertFalse(LoopbackBindPolicy.needsNetworkBinding("[::1]"))
        assertFalse(LoopbackBindPolicy.needsNetworkBinding("0:0:0:0:0:0:0:1"))
    }

    @Test
    fun `every address that reaches a real link still gets an interface chosen`() {
        // The P2P group owner, the hotspot, an ordinary LAN, and the 464XLAT gateway a
        // cellular-only device offers in the device list. None of these are local.
        assertTrue(LoopbackBindPolicy.needsNetworkBinding("192.168.49.1"))
        assertTrue(LoopbackBindPolicy.needsNetworkBinding("192.168.43.1"))
        assertTrue(LoopbackBindPolicy.needsNetworkBinding("10.0.0.2"))
        assertTrue(LoopbackBindPolicy.needsNetworkBinding("192.0.0.1"))
    }

    @Test
    fun `an address that merely starts with the digits is not loopback`() {
        assertTrue(LoopbackBindPolicy.needsNetworkBinding("127a.0.0.1"))
        assertTrue(LoopbackBindPolicy.needsNetworkBinding("1270.0.0.1"))
        assertTrue(LoopbackBindPolicy.needsNetworkBinding("12.7.0.1"))
        assertTrue(LoopbackBindPolicy.needsNetworkBinding("headunit.local"))
        assertTrue(LoopbackBindPolicy.needsNetworkBinding(""))
    }
}
