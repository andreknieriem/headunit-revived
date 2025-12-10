package com.andrerinas.headunitrevived.aap.protocol.messages

import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.proto.Media
import com.google.protobuf.Message

/**
 * @author algavris
 * *
 * @date 13/02/2017.
 */

class MediaAck(channel: Int, sessionId: Int)
    : AapMessage(channel, Media.MsgType.ACK_VALUE, makeProto(sessionId), ackBuf) {
    companion object {

        private val mediaAck = Media.Ack.newBuilder()
        private val ackBuf = ByteArray(20)

        private fun makeProto(sessionId: Int): Message {
            mediaAck.clear()
            mediaAck.sessionId = sessionId
            mediaAck.ack = 1
            // TODO: check creation of new object can be avoided
            return mediaAck.build()
        }
    }
}
