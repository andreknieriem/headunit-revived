package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationStandDownPolicyTest {

    // --- isAvailable: the guard lives in the device's framework, not in our target SDK ---

    @Test
    fun `below Q the platform honours it with no permission`() {
        assertTrue(StationStandDownPolicy.isAvailable(27, canDrawOverlays = false))
        assertTrue(StationStandDownPolicy.isAvailable(28, canDrawOverlays = false))
    }

    @Test
    fun `from Q to Android 14 the overlay permission is what gets past the guard`() {
        for (sdk in 29..34) {
            assertFalse("api $sdk", StationStandDownPolicy.isAvailable(sdk, canDrawOverlays = false))
            assertTrue("api $sdk", StationStandDownPolicy.isAvailable(sdk, canDrawOverlays = true))
        }
    }

    @Test
    fun `from Android 15 the bypass is gone and nothing works`() {
        assertFalse(StationStandDownPolicy.isAvailable(35, canDrawOverlays = true))
        assertFalse(StationStandDownPolicy.isAvailable(36, canDrawOverlays = true))
    }

    // --- shouldStandDown ---

    @Test
    fun `stands down when joined on a platform that allows it, with nothing to ask`() {
        assertTrue(
            StationStandDownPolicy.shouldStandDown(
                sdkInt = 27, canDrawOverlays = false,
                associated = true, networkId = 3
            )
        )
    }

    @Test
    fun `never when nothing is joined`() {
        assertFalse(
            StationStandDownPolicy.shouldStandDown(
                sdkInt = 27, canDrawOverlays = true,
                associated = false, networkId = 3
            )
        )
    }

    @Test
    fun `never on a hidden network id, which is what a redacted read looks like`() {
        assertFalse(
            StationStandDownPolicy.shouldStandDown(
                sdkInt = 27, canDrawOverlays = true,
                associated = true, networkId = -1
            )
        )
    }

    @Test
    fun `never where the platform would refuse the call`() {
        assertFalse(
            StationStandDownPolicy.shouldStandDown(
                sdkInt = 35, canDrawOverlays = true,
                associated = true, networkId = 3
            )
        )
    }

    @Test
    fun `the platform gate is the whole of it once a network is joined`() {
        for (sdk in 21..36) {
            for (overlay in listOf(false, true)) {
                assertEquals(
                    "api $sdk overlay=$overlay",
                    StationStandDownPolicy.isAvailable(sdk, overlay),
                    StationStandDownPolicy.shouldStandDown(sdk, overlay, associated = true, networkId = 3)
                )
            }
        }
    }

    // --- describeUnavailable: exactly the complement of isAvailable ---

    @Test
    fun `says nothing when it will work`() {
        assertNull(StationStandDownPolicy.describeUnavailable(27, canDrawOverlays = false))
        assertNull(StationStandDownPolicy.describeUnavailable(30, canDrawOverlays = true))
    }

    @Test
    fun `names the overlay permission where that is the missing piece`() {
        val why = StationStandDownPolicy.describeUnavailable(30, canDrawOverlays = false)
        assertNotNull(why)
        assertTrue(why!!.contains("display over other apps"))
    }

    @Test
    fun `says there is no route at all from Android 15`() {
        val why = StationStandDownPolicy.describeUnavailable(35, canDrawOverlays = true)
        assertNotNull(why)
        assertFalse(why!!.contains("display over other apps"))
    }

    @Test
    fun `an explanation is offered for every state that is not available`() {
        for (sdk in 21..36) {
            for (overlay in listOf(false, true)) {
                val available = StationStandDownPolicy.isAvailable(sdk, overlay)
                val why = StationStandDownPolicy.describeUnavailable(sdk, overlay)
                assertEquals("api $sdk overlay=$overlay", available, why == null)
            }
        }
    }

    // --- shouldRestore: a record with no restore is the one harmful outcome ---

    @Test
    fun `restores whenever a record is standing`() {
        assertTrue(StationStandDownPolicy.shouldRestore(0))
        assertTrue(StationStandDownPolicy.shouldRestore(7))
    }

    @Test
    fun `nothing to restore when no record is standing`() {
        assertFalse(StationStandDownPolicy.shouldRestore(-1))
    }
}
