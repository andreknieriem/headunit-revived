package com.andrerinas.headunitrevived.aap

import android.content.Context
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

/**
 * @author algavris
 * *
 * @date 13/02/2017.
 */

internal class AapMessageHandlerType(
        private val transport: AapTransport,
        recorder: MicRecorder,
        private val aapAudio: AapAudio,
        private val aapVideo: AapVideo,
        settings: Settings,
        backgroundNotification: BackgroundNotification,
        context: Context) : AapMessageHandler {

    private val aapControl: AapControl = AapControlGateway(transport, recorder, aapAudio, settings, context)
    private val mediaPlayback = AapMediaPlayback(backgroundNotification)

    @Throws(AapMessageHandler.HandleException::class)
    override fun handle(message: AapMessage) {

        val msgType = message.type
        val flags = message.flags

        if (message.isAudio && (msgType == 0 || msgType == 1)) {
            transport.sendMediaAck(message.channel)
            aapAudio.process(message)
            // 300 ms @ 48000/sec   samples = 14400     stereo 16 bit results in bytes = 57600
        } else if (message.isVideo && (msgType == 0 || msgType == 1 || flags.toInt() == 8 || flags.toInt() == 9 || flags.toInt() == 10)) {
            transport.sendMediaAck(message.channel)
            aapVideo.process(message)
        } else if (message.channel == Channel.ID_MPB && msgType > 31) {
            mediaPlayback.process(message)
        } else if (msgType in 0..31 || msgType in 32768..32799 || msgType in 65504..65535) {
            try {
                aapControl.execute(message)
            } catch (e: Exception) {
                AppLog.e(e)
                throw AapMessageHandler.HandleException(e)
            }
        } else {
            AppLog.e("Unknown msg_type: %d, flags: %d", msgType, flags)
        }

    }
}
