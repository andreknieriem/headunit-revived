package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.direct.NativeRefreshPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeRefreshPolicyTest {

    @Test
    fun `a group that is up and ours has its credentials read again`() {
        assertEquals(Action.REDELIVER, NativeRefreshPolicy.decide(groupExists = true, isGroupOwner = true, createInFlightForMs = null))
        // Even mid-create: the group answering is what ends the create.
        assertEquals(Action.REDELIVER, NativeRefreshPolicy.decide(groupExists = true, isGroupOwner = true, createInFlightForMs = 500))
    }

    @Test
    fun `a create that was just asked for is left to answer`() {
        assertEquals(Action.WAIT, NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = 0))
        assertEquals(Action.WAIT, NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = NativeRefreshPolicy.CREATE_GRACE_MS - 1))
    }

    @Test
    fun `a create that has gone unanswered past the grace is remade`() {
        assertEquals(Action.RECREATE, NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = NativeRefreshPolicy.CREATE_GRACE_MS))
    }

    @Test
    fun `no group and nothing in flight is remade`() {
        assertEquals(Action.RECREATE, NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = null))
    }

    @Test
    fun `a group we are only a client of is not ours to hand out`() {
        assertEquals(Action.RECREATE, NativeRefreshPolicy.decide(groupExists = true, isGroupOwner = false, createInFlightForMs = null))
    }
}
