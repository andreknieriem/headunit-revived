package com.andrerinas.headunitrevived.aap.protocol.messages

import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.Input
import com.google.protobuf.Message

/**
 * @author algavris
 * *
 * @date 13/02/2017.
 */

class ScrollWheelEvent(timeStamp: Long, delta: Int)
    : AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, makeProto(timeStamp, delta)) {
    companion object {
        const val KEYCODE_SCROLL_WHEEL = 65536

        private fun makeProto(timeStamp: Long, delta: Int): Message {

            return Input.InputReport.newBuilder().also {
                it.timestamp = timeStamp * 1000000L
                it.keyEvent = Input.KeyEvent.newBuilder().build() // TODO: check if requred
                it.relativeEvent = Input.RelativeEvent.newBuilder().also { event ->
                    event.addData(Input.RelativeEvent_Rel.newBuilder().apply {
                        setDelta(delta)
                        keycode = KEYCODE_SCROLL_WHEEL
                    })
                }.build()
            }.build()

        }
    }

}
