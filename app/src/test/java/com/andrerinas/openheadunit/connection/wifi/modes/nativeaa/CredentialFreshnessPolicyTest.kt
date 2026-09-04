package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.CredentialFreshnessPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialFreshnessPolicyTest {

    private val captured = NativeNetworkCredentials(
        ssid = "DIRECT-VQ-Navegadortz2",
        psk = "abcd1234",
        ip = "192.168.49.1",
        bssid = "7A:3E:20:5A:12:2F",
    )

    @Test
    fun `an unchanged network is sent as it was captured`() {
        // A distinct instance carrying the same values: the group was never touched.
        val live = NativeNetworkCredentials(captured.ssid, captured.psk, captured.ip, captured.bssid)
        assertEquals(Action.SEND_AS_CAPTURED, CredentialFreshnessPolicy.decide(captured, live))
    }

    @Test
    fun `a network that was taken down is not named at all`() {
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, null))
    }

    @Test
    fun `a moved endpoint makes the whole exchange stale`() {
        // Type 1 already told the phone where to dial, so a new address cannot be patched in here.
        val live = captured.copy(ip = "192.168.49.5")
        assertEquals(Action.ABORT, CredentialFreshnessPolicy.decide(captured, live))
    }

    @Test
    fun `a replaced group is sent as it is now`() {
        assertEquals(Action.SEND_LIVE, CredentialFreshnessPolicy.decide(captured, captured.copy(ssid = "DIRECT-ET-Navegadortz2")))
        assertEquals(Action.SEND_LIVE, CredentialFreshnessPolicy.decide(captured, captured.copy(bssid = "7E:D6:68:AC:67:06")))
        assertEquals(Action.SEND_LIVE, CredentialFreshnessPolicy.decide(captured, captured.copy(psk = "wxyz9876")))
    }
}
