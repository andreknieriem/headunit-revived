package com.andrerinas.headunitrevived.aap

import android.content.Context
import android.media.AudioManager
import com.andrerinas.headunitrevived.aap.protocol.AudioConfigs
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.messages.DrivingStatusEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.ServiceDiscoveryResponse
import com.andrerinas.headunitrevived.aap.protocol.proto.Common
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.aap.protocol.proto.Input
import com.andrerinas.headunitrevived.aap.protocol.proto.Media
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

/**
 * @author algavris
 * *
 * @date 01/10/2016.
 */

interface AapControl {
    fun execute(message: AapMessage): Int
}

internal class AapControlMedia(
    private val aapTransport: AapTransport,
    private val micRecorder: MicRecorder,
    private val aapAudio: AapAudio): AapControl {

    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Media.MsgType.SETUPREQUEST_VALUE -> {
                val setupRequest = message.parse(Media.MediaSetupRequest.newBuilder()).build()
                return mediaSinkSetupRequest(setupRequest, message.channel)
            }
            Media.MsgType.STARTREQUEST_VALUE -> {
                val startRequest = message.parse(Media.Start.newBuilder()).build()
                return mediaStartRequest(startRequest, message.channel)
            }
            Media.MsgType.STOPREQUEST_VALUE -> return mediaSinkStopRequest(message.channel)
            Media.MsgType.VIDEOFOCUSREQUESTNOTIFICATION_VALUE -> {
                val focusRequest = message.parse(Media.VideoFocusRequestNotification.newBuilder()).build()
                AppLog.i("Video Focus Request - disp_id: %s, mode: %s, reason: %s", if (focusRequest.hasDispChannelId()) focusRequest.dispChannelId else "N/A", focusRequest.mode, if (focusRequest.hasReason()) focusRequest.reason else "N/A")
                return 0
            }
            Media.MsgType.MICREQUEST_VALUE -> {
                val micRequest = message.parse(Media.MicrophoneRequest.newBuilder()).build()
                return micRequest(micRequest)
            }
            Media.MsgType.ACK_VALUE -> return 0
            else -> AppLog.e("Unsupported")
        }
        return 0
    }

    private fun mediaStartRequest(request: Media.Start, channel: Int): Int {
        AppLog.i("Media Start Request %s: %s", Channel.name(channel), request)

        aapTransport.setSessionId(channel, request.sessionId)
        return 0
    }

    private fun mediaSinkSetupRequest(request: Media.MediaSetupRequest, channel: Int): Int {

        AppLog.i("Media Sink Setup Request: %d", request.type)
        // R 2 VID b 00000000 08 03
        // R 4 AUD b 00000000 08 01

        val configResponse = Media.Config.newBuilder().apply {
            status = Media.Config.ConfigStatus.HEADUNIT
            maxUnacked = 1
            addConfigurationIndices(0)
        }.build()
        AppLog.i("Config response: %s", configResponse)
        val msg = AapMessage(channel, Media.MsgType.CONFIGRESPONSE_VALUE, configResponse)
        AppLog.i(AapDump.logHex(msg))
        aapTransport.send(msg)

        if (channel == Channel.ID_VID) {
            aapTransport.gainVideoFocus()
        }

        return 0
    }

    private fun mediaSinkStopRequest(channel: Int): Int {
        AppLog.i("Media Sink Stop Request: " + Channel.name(channel))
        if (Channel.isAudio(channel)) {
            aapAudio.stopAudio(channel)
        }
        return 0
    }

    private fun micRequest(micRequest: Media.MicrophoneRequest): Int {
        AppLog.d("Mic request: %s", micRequest)

        if (micRequest.open) {
            micRecorder.start()
        } else {
            micRecorder.stop()
        }
        return 0
    }
}

internal class AapControlTouch(private val aapTransport: AapTransport): AapControl {

    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Input.MsgType.BINDINGREQUEST_VALUE -> {
                val request = message.parse(Input.KeyBindingRequest.newBuilder()).build()
                return inputBinding(request, message.channel)
            }
            else -> AppLog.e("Unsupported")
        }
        return 0
    }

    private fun inputBinding(request: Input.KeyBindingRequest, channel: Int): Int {
        AppLog.i("Input binding request %s", request)
        aapTransport.send(AapMessage(channel, Input.MsgType.BINDINGRESPONSE_VALUE, Input.BindingResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_OK)
                .build()))
        return 0
    }

}

internal class AapControlSensor(private val aapTransport: AapTransport): AapControl {

    override fun execute(message: AapMessage): Int {
        // 0 - 31, 32768-32799, 65504-65535
        when (message.type) {
            Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE -> {
                val request = message.parse(Sensors.SensorRequest.newBuilder()).build()
                return sensorStartRequest(request, message.channel)
            }
            else -> AppLog.e("Unsupported")
        }
        return 0
    }

    private fun sensorStartRequest(request: Sensors.SensorRequest, channel: Int): Int {
        AppLog.i("Sensor Start Request sensor: %s, minUpdatePeriod: %d", request.type.name, request.minUpdatePeriod)

        // R 1 SEN b 00000000 08 01 10 00     Sen: 1, 10, 3, 8, 7
        // Yes: SENSOR_TYPE_COMPASS/LOCATION/RPM/DIAGNOSTICS/GEAR      No: SENSOR_TYPE_DRIVING_STATUS

        val msg = AapMessage(channel, Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE, Sensors.SensorResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_OK)
                .build())
        AppLog.i(msg.toString())

        aapTransport.send(msg)
        aapTransport.startSensor(request.type.number)
        return 0
    }
}

internal class AapControlService(
        private val aapTransport: AapTransport,
        private val aapAudio: AapAudio,
        private val settings: Settings,
        private val context: Context): AapControl {

    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Control.ControlMsgType.SERVICEDISCOVERYREQUEST_VALUE -> {
                val request = message.parse(Control.ServiceDiscoveryRequest.newBuilder()).build()
                return serviceDiscoveryRequest(request)
            }
            Control.ControlMsgType.PINGREQUEST_VALUE -> {
                val pingRequest = message.parse(Control.PingRequest.newBuilder()).build()
                return pingRequest(pingRequest, message.channel)
            }
            Control.ControlMsgType.NAVFOCUSREQUESTNOTIFICATION_VALUE -> {
                val navigationFocusRequest = message.parse(Control.NavFocusRequestNotification.newBuilder()).build()
                return navigationFocusRequest(navigationFocusRequest, message.channel)
            }
            Control.ControlMsgType.BYEYEREQUEST_VALUE -> {
                val shutdownRequest = message.parse(Control.ByeByeRequest.newBuilder()).build()
                return byebyeRequest(shutdownRequest, message.channel)
            }
            Control.ControlMsgType.BYEYERESPONSE_VALUE -> {
                AppLog.i("Byebye Response")
                return -1
            }
            Control.ControlMsgType.VOICESESSIONNOTIFICATION_VALUE -> {
                val voiceRequest = message.parse(Control.VoiceSessionNotification.newBuilder()).build()
                return voiceSessionNotification(voiceRequest)
            }
            Control.ControlMsgType.AUDIOFOCUSREQUESTNOTFICATION_VALUE -> {
                val audioFocusRequest = message.parse(Control.AudioFocusRequestNotification.newBuilder()).build()
                return audioFocusRequest(audioFocusRequest, message.channel)
            }
            else -> AppLog.e("Unsupported")
        }
        return 0
    }


    private fun serviceDiscoveryRequest(request: Control.ServiceDiscoveryRequest): Int {                  // Service Discovery Request
        AppLog.i("Service Discovery Request: %s", request.phoneName)                               // S 0 CTR b src: HU  lft:   113  msg_type:     6 Service Discovery Response    S 0 CTR b 00000000 0a 08 08 01 12 04 0a 02 08 0b 0a 13 08 02 1a 0f

        val msg = ServiceDiscoveryResponse(context)
        AppLog.i(msg.toString())

        aapTransport.send(msg)
        return 0
    }

    private fun pingRequest(request: Control.PingRequest, channel: Int): Int {
        AppLog.i("Ping Request: %d", request.timestamp)

        // Channel Open Response
        val response = Control.PingResponse.newBuilder()
                .setTimestamp(System.nanoTime())
                .build()

        val msg = AapMessage(channel, Control.ControlMsgType.PINGRESPONSE_VALUE, response)
        AppLog.i(msg.toString())

        aapTransport.send(msg)
        return 0
    }

    private fun navigationFocusRequest(request: Control.NavFocusRequestNotification, channel: Int): Int {
        AppLog.i("Navigation Focus Request: %s", request.focusType)

        // Send Navigation Focus Notification
        val response = Control.NavFocusNotification.newBuilder()
                .setFocusType(Control.NavFocusType.NAV_FOCUS_2)
                .build()

        val msg = AapMessage(channel, Control.ControlMsgType.NAVFOCUSRNOTIFICATION_VALUE, response)
        AppLog.i(msg.toString())

        aapTransport.send(msg)
        return 0
    }

    private fun byebyeRequest(request: Control.ByeByeRequest, channel: Int): Int {
        if (request.reason == Control.ByeByeRequest.ByeByeReason.QUIT)
            AppLog.i("Byebye Request reason: 1 AA Exit Car Mode")
        else
            AppLog.e("Byebye Request reason: %s", request.reason)

        val msg = AapMessage(channel, Control.ControlMsgType.BYEYERESPONSE_VALUE, Control.ByeByeResponse.newBuilder().build())
        AppLog.i(msg.toString())
        aapTransport.send(msg)
        Utils.ms_sleep(100)
        aapTransport.quit()
        return -1
    }

    private fun voiceSessionNotification(request: Control.VoiceSessionNotification): Int {
        // sr:  00000000 00 11 08 01      Microphone voice search usage     sr:  00000000 00 11 08 02
        if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_START)
            AppLog.i("Voice Session Notification: 1 START")
        else if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_STOP)
            AppLog.i("Voice Session Notification: 2 STOP")
        else
            AppLog.e("Voice Session Notification: %d", request.status)
        return 0
    }

    private fun audioFocusRequest(notification: Control.AudioFocusRequestNotification, channel: Int): Int {
        AppLog.i("Audio Focus Request: ${notification.request}")

        aapAudio.requestFocusChange(AudioConfigs.stream(channel), notification.request.number, AudioManager.OnAudioFocusChangeListener {
            val response = Control.AudioFocusNotification.newBuilder()

            focusResponse[notification.request]?.let { newSate ->
                response.focusState = newSate
                AppLog.i("Audio Focus new state: $newSate, system focus change: $it ${systemFocusName[it]}")

                val msg = AapMessage(channel, Control.ControlMsgType.AUDIOFOCUSNOTFICATION_VALUE, response.build())
                AppLog.i(msg.toString())
                aapTransport.send(msg)
            }
        })

        return 0
    }

    companion object {
        private val systemFocusName = mapOf(
                AudioManager.AUDIOFOCUS_GAIN to "AUDIOFOCUS_GAIN",
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT to "AUDIOFOCUS_GAIN_TRANSIENT",
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE to "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE",
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK to "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK",
                AudioManager.AUDIOFOCUS_LOSS to "AUDIOFOCUS_LOSS",
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT to "AUDIOFOCUS_LOSS_TRANSIENT",
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK to "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK",
                AudioManager.AUDIOFOCUS_NONE to "AUDIOFOCUS_NONE"
        )

        private val focusResponse = mapOf(
            Control.AudioFocusRequestNotification.AudioFocusRequestType.RELEASE to Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT,
            Control.AudioFocusRequestNotification.AudioFocusRequestType.GAIN_TRANSIENT_MAY_DUCK to Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN_TRANSIENT_GUIDANCE_ONLY
        )
    }
}

internal class AapControlGateway(
        private val aapTransport: AapTransport,
        private val serviceControl: AapControl,
        private val mediaControl: AapControl,
        private val touchControl: AapControl,
        private val sensorControl: AapControl): AapControl {

    constructor(aapTransport: AapTransport,
                micRecorder: MicRecorder,
                aapAudio: AapAudio,
                settings: Settings,
                context: Context) : this(
            aapTransport,
            AapControlService(aapTransport, aapAudio, settings, context),
            AapControlMedia(aapTransport, micRecorder, aapAudio),
            AapControlTouch(aapTransport),
            AapControlSensor(aapTransport))

    override fun execute(message: AapMessage): Int {

        if (message.type == 7) {
            val request = message.parse(Control.ChannelOpenRequest.newBuilder()).build()
            return channelOpenRequest(request, message.channel)
        }

        when (message.channel) {
            Channel.ID_CTR -> return serviceControl.execute(message)
            Channel.ID_INP -> return touchControl.execute(message)
            Channel.ID_SEN -> return sensorControl.execute(message)
            Channel.ID_VID, Channel.ID_AUD, Channel.ID_AU1, Channel.ID_AU2, Channel.ID_MIC -> return mediaControl.execute(message)
        }
        return 0
    }

    private fun channelOpenRequest(request: Control.ChannelOpenRequest, channel: Int): Int {
        // Channel Open Request
        AppLog.i("Channel Open Request - priority: %d  chan: %d %s", request.priority, request.serviceId, Channel.name(request.serviceId))

        val msg = AapMessage(channel, Control.ControlMsgType.CHANNELOPENRESPONSE_VALUE, Control.ChannelOpenResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_OK)
                .build())
        AppLog.i(msg.toString())

        aapTransport.send(msg)

        if (channel == Channel.ID_SEN) {
            Utils.ms_sleep(2)
            AppLog.i("Send driving status")
            aapTransport.send(DrivingStatusEvent(Sensors.SensorBatch.DrivingStatusData.Status.UNRESTRICTED))
        }
        return 0
    }
}