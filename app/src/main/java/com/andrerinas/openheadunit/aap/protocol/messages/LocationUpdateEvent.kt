package com.andrerinas.openheadunit.aap.protocol.messages

import android.location.Location
import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class LocationUpdateEvent(location: Location)
    : SensorEvent(Sensors.SensorType.LOCATION_VALUE, makeProto(location)) {

    companion object {
        private fun makeProto(location: Location): Message {
            return Sensors.SensorBatch.newBuilder().also { batch ->
                batch.addLocationData(
                    Sensors.SensorBatch.LocationData.newBuilder().apply {
                        timestamp = location.time
                        latitude = (location.latitude * 1E7).toInt()
                        longitude = (location.longitude * 1E7).toInt()
                        if (location.hasAltitude()) {
                            altitude = (location.altitude * 1E2).toInt()
                        }
                        if (location.hasBearing()) {
                            bearing = (location.bearing * 1E6).toInt()
                        }
                        if (location.hasSpeed()) {
                            speed = (location.speed * 1E3).toInt()
                        }
                        if (location.hasAccuracy()) {
                            accuracy = (location.accuracy * 1E3).toInt()
                        }
                    }
                )
            }.build()
        }
    }
}
