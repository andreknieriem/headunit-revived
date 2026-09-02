package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HfpServiceRecordPolicyTest {

    private val handsFree = HfpServiceRecordPolicy.HANDS_FREE_UUID
    private val audioGateway = "0000111f-0000-1000-8000-00805f9b34fb"
    private val a2dpSink = "0000110b-0000-1000-8000-00805f9b34fb"

    @Test
    fun `a device already advertising hands-free does not get a second record`() {
        assertFalse(HfpServiceRecordPolicy.shouldRegisterDummyHfp(listOf(a2dpSink, handsFree)))
    }

    @Test
    fun `the comparison ignores case`() {
        assertFalse(HfpServiceRecordPolicy.shouldRegisterDummyHfp(listOf(handsFree.uppercase())))
    }

    @Test
    fun `a phone advertising audio gateway still gets the record`() {
        // A phone standing in for a head unit carries the other half of HFP, so it needs ours.
        assertTrue(HfpServiceRecordPolicy.shouldRegisterDummyHfp(listOf(audioGateway, a2dpSink)))
    }

    @Test
    fun `an adapter with nothing advertised gets the record`() {
        assertTrue(HfpServiceRecordPolicy.shouldRegisterDummyHfp(emptyList()))
    }

    @Test
    fun `an adapter that could not be asked gets the record`() {
        // Reading the local UUIDs is not public API; a refusal is not an answer of yes.
        assertTrue(HfpServiceRecordPolicy.shouldRegisterDummyHfp(null))
    }

    private fun opens(
        enabled: Boolean = true,
        publishedStandIn: Boolean = true,
        link: BluetoothWakePolicy.HandsFreeLink = BluetoothWakePolicy.HandsFreeLink.ABSENT,
    ) = HfpServiceRecordPolicy.shouldOpenServiceLevelConnection(enabled, publishedStandIn, link)

    @Test
    fun `a live hands-free link keeps the stand-in from speaking first`() {
        assertFalse(opens(link = BluetoothWakePolicy.HandsFreeLink.CONNECTED))
    }

    @Test
    fun `no hands-free link lets the stand-in open the exchange`() {
        assertTrue(opens(link = BluetoothWakePolicy.HandsFreeLink.ABSENT))
    }

    @Test
    fun `an adapter that would not say still opens the exchange`() {
        // Same rule as the record above: a question that could not be asked is not answered yes.
        assertTrue(opens(link = BluetoothWakePolicy.HandsFreeLink.UNREADABLE))
    }

    @Test
    fun `the setting off stops the stand-in speaking first`() {
        assertFalse(opens(enabled = false))
    }

    @Test
    fun `a radio that publishes no stand-in record never speaks first`() {
        assertFalse(opens(publishedStandIn = false))
    }

    @Test
    fun `only a readable live link stands the stand-in down, as with the poke`() {
        // The two predicates answer UNREADABLE the same way and for the same stated reason.
        // Collapsing either one alone would silently disable a mechanism on a radio that will not
        // report its profiles.
        for (link in BluetoothWakePolicy.HandsFreeLink.entries) {
            assertEquals(BluetoothWakePolicy.shouldPoke(link), opens(link = link))
        }
    }
}
