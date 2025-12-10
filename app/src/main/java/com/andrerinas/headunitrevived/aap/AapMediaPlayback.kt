package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.aap.protocol.proto.MediaPlayback
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.utils.AppLog
import java.nio.ByteBuffer

/**
 * @author algavris
 * @date 08/07/2017
 */

class AapMediaPlayback(private val notification: BackgroundNotification) {
    private val messageBuffer = ByteBuffer.allocate(Messages.DEF_BUFFER_LENGTH * 2)
    private var started = false

    fun process(message: AapMessage) {

        val flags = message.flags.toInt()

        when (message.type) {
            MediaPlayback.MsgType.MSG_PLAYBACK_METADATA_VALUE -> {
                val request = message.parse(MediaPlayback.MediaMetaData.newBuilder()).build()
                notifyRequest(request)
            }
            MediaPlayback.MsgType.MSG_PLAYBACK_METADATASTART_VALUE -> {
                if (flags == 0x09) {
                    messageBuffer.put(message.data, message.dataOffset, message.size - message.dataOffset)
                    this.started = true
                    // If First fragment
                }
            } else -> {
                if (this.started) {
                    if (flags == 0x08) {
                        messageBuffer.put(message.data, 0, message.size)
                        return
                        // If Middle fragment
                    } else if (flags == 0xa) {
                        messageBuffer.put(message.data, 0 , message.size)
                        messageBuffer.flip()
                        try {
                            val request = MediaPlayback.MediaMetaData.newBuilder()
                                    .mergeFrom(messageBuffer.array(), 0, messageBuffer.limit())
                                    .build()
                            notifyRequest(request)
                        } catch (e: Exception) {
                            AppLog.e(e)
                        }
                        this.started = false
                        messageBuffer.clear()
                        // If Last fragment
                        return
                    }
                }
                AppLog.e("Unsupported %s", message.toString())
            }
        }
    }

    private fun notifyRequest(request: MediaPlayback.MediaMetaData) {
        // notification.notify(request)
    }

}