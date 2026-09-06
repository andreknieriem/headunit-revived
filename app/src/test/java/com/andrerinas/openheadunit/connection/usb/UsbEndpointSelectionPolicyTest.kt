package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.usb.UsbEndpointSelectionPolicy.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which endpoint pair AAP runs over. Both USB transports ask this. */
class UsbEndpointSelectionPolicyTest {

    private fun bulkIn() = Endpoint(isInbound = true, isBulk = true)
    private fun bulkOut() = Endpoint(isInbound = false, isBulk = true)
    private fun interruptIn() = Endpoint(isInbound = true, isBulk = false)
    private fun interruptOut() = Endpoint(isInbound = false, isBulk = false)

    /** What AOAP actually gives us on 0x2D00: one interface, one bulk pair. */
    @Test
    fun `the plain accessory interface picks its only pair`() {
        val selection = UsbEndpointSelectionPolicy.select(listOf(bulkIn(), bulkOut()))

        assertEquals(0, selection.inIndex)
        assertEquals(1, selection.outIndex)
        assertTrue(selection.isComplete)
    }

    /** The bug: an interrupt endpoint listed first used to win, and every transfer then failed. */
    @Test
    fun `an interrupt endpoint never beats a bulk one in the same direction`() {
        val selection = UsbEndpointSelectionPolicy.select(
            listOf(interruptIn(), bulkOut(), bulkIn())
        )

        assertEquals(2, selection.inIndex)
        assertEquals(1, selection.outIndex)
    }

    @Test
    fun `order within the bulk endpoints does not matter`() {
        val selection = UsbEndpointSelectionPolicy.select(listOf(bulkOut(), bulkIn()))

        assertEquals(1, selection.inIndex)
        assertEquals(0, selection.outIndex)
    }

    /** Falling back to any type keeps the behaviour both transports had before. */
    @Test
    fun `a device with no bulk endpoints still yields a pair`() {
        val selection = UsbEndpointSelectionPolicy.select(listOf(interruptIn(), interruptOut()))

        assertEquals(0, selection.inIndex)
        assertEquals(1, selection.outIndex)
        assertTrue(selection.isComplete)
    }

    /** A half-bulk interface takes the bulk side and falls back only for the direction that lacks one. */
    @Test
    fun `the fallback fills only the direction that has no bulk endpoint`() {
        val selection = UsbEndpointSelectionPolicy.select(listOf(interruptOut(), bulkIn()))

        assertEquals(1, selection.inIndex)
        assertEquals(0, selection.outIndex)
    }

    @Test
    fun `a one-directional interface is refused rather than half claimed`() {
        val selection = UsbEndpointSelectionPolicy.select(listOf(bulkIn(), interruptIn()))

        assertEquals(0, selection.inIndex)
        assertNull(selection.outIndex)
        assertFalse(selection.isComplete)
    }

    @Test
    fun `an interface with no endpoints is refused`() {
        val selection = UsbEndpointSelectionPolicy.select(emptyList())

        assertNull(selection.inIndex)
        assertNull(selection.outIndex)
        assertFalse(selection.isComplete)
    }
}
