package com.andrerinas.openheadunit.connection.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAttachPolicyTest {

    @Test
    fun `google vendor is always attempted`() {
        assertTrue(
            UsbAttachPolicy.shouldAttemptAoaSwitch(
                isGoogleVendor = true,
                autoStartOnUsb = false,
                allowListConfigured = true,
                deviceAllowed = false,
            )
        )
    }

    @Test
    fun `auto start on usb overrides the allow list`() {
        assertTrue(
            UsbAttachPolicy.shouldAttemptAoaSwitch(
                isGoogleVendor = false,
                autoStartOnUsb = true,
                allowListConfigured = true,
                deviceAllowed = false,
            )
        )
    }

    /**
     * The reporter's case. A Samsung phone (0x04E8), auto-start off, and a fresh install whose
     * allow list has never been written. This returned false before and was the whole defect.
     */
    @Test
    fun `unconfigured allow list lets an android phone through`() {
        assertTrue(
            UsbAttachPolicy.shouldAttemptAoaSwitch(
                isGoogleVendor = false,
                autoStartOnUsb = false,
                allowListConfigured = false,
                deviceAllowed = false,
            )
        )
    }

    @Test
    fun `a configured allow list still excludes a device that is not on it`() {
        assertFalse(
            UsbAttachPolicy.shouldAttemptAoaSwitch(
                isGoogleVendor = false,
                autoStartOnUsb = false,
                allowListConfigured = true,
                deviceAllowed = false,
            )
        )
    }

    @Test
    fun `a configured allow list admits a device that is on it`() {
        assertTrue(
            UsbAttachPolicy.shouldAttemptAoaSwitch(
                isGoogleVendor = false,
                autoStartOnUsb = false,
                allowListConfigured = true,
                deviceAllowed = true,
            )
        )
    }

    /** The only combination that may refuse: a list exists and this device is absent from it. */
    @Test
    fun `refusal requires a configured list that excludes the device`() {
        for (google in listOf(true, false)) {
            for (autoStart in listOf(true, false)) {
                for (configured in listOf(true, false)) {
                    for (allowed in listOf(true, false)) {
                        val attempt = UsbAttachPolicy.shouldAttemptAoaSwitch(
                            google, autoStart, configured, allowed
                        )
                        if (!attempt) {
                            assertFalse(google)
                            assertFalse(autoStart)
                            assertTrue(configured)
                            assertFalse(allowed)
                        }
                    }
                }
            }
        }
    }
}
