package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** One announced id per vehicle type, so the phone records each type under its own entry. */
class VehicleIdentityPolicyTest {

    @Test
    fun `a car announces the id the user set`() {
        assertEquals("hu-1", VehicleIdentityPolicy.vehicleId("hu-1", VehicleTypePolicy.CAR))
    }

    @Test
    fun `every type announces a different id`() {
        val ids = VehicleTypePolicy.SELECTABLE.map { VehicleIdentityPolicy.vehicleId("hu-1", it) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the id is stable, so each type is recorded once and reconnects cleanly`() {
        val first = VehicleIdentityPolicy.vehicleId("hu-1", VehicleTypePolicy.MOTORCYCLE)
        assertEquals(first, VehicleIdentityPolicy.vehicleId("hu-1", VehicleTypePolicy.MOTORCYCLE))
        assertNotEquals(first, VehicleIdentityPolicy.vehicleId("hu-1", VehicleTypePolicy.CAR))
    }

    @Test
    fun `a different base id stays different`() {
        assertNotEquals(
            VehicleIdentityPolicy.vehicleId("hu-1", VehicleTypePolicy.TRUCK),
            VehicleIdentityPolicy.vehicleId("hu-2", VehicleTypePolicy.TRUCK)
        )
    }
}
