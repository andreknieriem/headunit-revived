package com.andrerinas.headunitrevived.aap.protocol.messages

import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.google.protobuf.Message

/**
 * @author algavris
 * *
 * @date 24/02/2017.
 */

open class SensorEvent(val sensorType: Int, proto: Message)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, proto)
