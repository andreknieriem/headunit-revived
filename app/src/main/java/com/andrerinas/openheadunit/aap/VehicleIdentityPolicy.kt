package com.andrerinas.openheadunit.aap

/**
 * The vehicle id this head unit announces, which is part of how Android Auto identifies it.
 *
 * The phone looks its stored record up by make, model, year and a hash of this id, and on a hit it
 * stamps the stored vehicle type over the one we declare. The hash also folds in the phone's own
 * android_id and our TLS certificate subject, so we can only move it by moving the id. Giving each
 * type its own id misses that lookup, which is what lets a changed type survive to the session.
 *
 * Pure: no Android, no logging.
 */
object VehicleIdentityPolicy {

    /** A car keeps the user's own id, so nothing changes for a head unit that never claims another type. */
    fun vehicleId(base: String, vehicleType: Int): String = when (vehicleType) {
        VehicleTypePolicy.TRUCK -> "$base-truck"
        VehicleTypePolicy.MOTORCYCLE -> "$base-moto"
        else -> base
    }
}
