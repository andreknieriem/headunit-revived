package com.andrerinas.headunitrevived.aap.protocol.messages

import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.Media
import com.google.protobuf.Message

/**
 * @author algavris
 * *
 * @date 13/02/2017.
 */

class VideoFocusEvent(gain: Boolean, unsolicited: Boolean)
    : AapMessage(Channel.ID_VID, Media.MsgType.VIDEOFOCUSNOTIFICATION_VALUE, makeProto(gain, unsolicited)) {

    companion object {
        private fun makeProto(gain: Boolean, unsolicited: Boolean): Message {
            return Media.VideoFocusNotification.newBuilder().apply {
                mode = if (gain) Media.VideoFocusMode.FOCUSED else Media.VideoFocusMode.UNFOCUSED
                setUnsolicited(unsolicited)
            }.build()
        }
    }
}
