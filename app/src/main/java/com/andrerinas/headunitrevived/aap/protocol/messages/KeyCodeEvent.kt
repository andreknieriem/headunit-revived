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

class KeyCodeEvent(timeStamp: Long, keycode: Int, isPress: Boolean)
    : AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, makeProto(timeStamp, keycode, isPress)) {

    companion object {
        private fun makeProto(timeStamp: Long, keycode: Int, isPress: Boolean): Message {
            return Input.InputReport.newBuilder().also {
                it.timestamp = timeStamp * 1000000L
                it.keyEvent = Input.KeyEvent.newBuilder().apply {
                    addKeys(Input.Key.newBuilder().also { key ->
                        key.keycode = keycode
                        key.down = isPress
                    })
                }.build()
            }.build()
        }
    }
}
