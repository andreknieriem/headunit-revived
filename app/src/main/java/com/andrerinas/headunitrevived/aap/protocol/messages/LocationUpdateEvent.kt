package com.andrerinas.headunitrevived.aap.protocol.messages

import android.location.Location
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class LocationUpdateEvent(location: Location)
    : SensorEvent(Sensors.SensorType.LOCATION_VALUE, makeProto(location)) {

    companion object {
        private fun makeProto(location: Location): Message {
            return Sensors.SensorBatch.newBuilder().also {
                it.addLocationData(
                        Sensors.SensorBatch.LocationData.newBuilder().apply {
                            timestamp = location.time
                            latitude = (location.latitude * 1E7).toInt()
                            longitude = (location.longitude * 1E7).toInt()
                            altitude = (location.altitude * 1E2).toInt()
                            bearing = (location.bearing * 1E6).toInt()
                            // AA expects speed in mm/s (m/s * 1000)
                            speed = (location.speed * 1E3).toInt()
                            accuracy = (location.accuracy * 1E3).toInt()
                        }
                )
            }.build()
        }
    }
}
