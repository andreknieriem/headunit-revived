package com.andrerinas.headunitrevived.aap

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.andrerinas.headunitrevived.aap.protocol.AudioConfigs
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.messages.DrivingStatusEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.ServiceDiscoveryResponse
import com.andrerinas.headunitrevived.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.headunitrevived.aap.protocol.proto.Common
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.aap.protocol.proto.Input
import com.andrerinas.headunitrevived.aap.protocol.proto.Media
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

interface AapControl {
    fun execute(message: AapMessage): Int
}

internal class AapControlMedia(
    private val aapTransport: AapTransport,
    private val micRecorder: MicRecorder,
    private val aapAudio: AapAudio): AapControl {

    private var lastNativeFocusRequestTime = 0L
    private var nativeFocusRequestCount = 0

    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE -> {
                val setupRequest = message.parse(Media.MediaSetupRequest.newBuilder()).build()
                return mediaSinkSetupRequest(setupRequest, message.channel)
            }
            Media.MsgType.MEDIA_MESSAGE_START_VALUE -> {
                val startRequest = message.parse(Media.Start.newBuilder()).build()
                return mediaStartRequest(startRequest, message.channel)
            }
            Media.MsgType.MEDIA_MESSAGE_STOP_VALUE -> return mediaSinkStopRequest(message.channel)
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST_VALUE -> {
                val focusRequest = message.parse(Media.VideoFocusRequestNotification.newBuilder()).build()
                AppLog.i("RX: Video Focus Request - mode: %s, reason: %s", focusRequest.mode, focusRequest.reason)

                if (focusRequest.mode == Media.VideoFocusMode.VIDEO_FOCUS_NATIVE) {
                    AppLog.i("Video Focus NATIVE received. User likely clicked Exit. Stopping transport.")
                    aapTransport.wasUserExit = true
                    aapTransport.stop()
                }
                return 0
            }
            Media.MsgType.MEDIA_MESSAGE_MICROPHONE_REQUEST_VALUE -> {
                val micRequest = message.parse(Media.MicrophoneRequest.newBuilder()).build()
                return micRequest(micRequest)
            }
            Media.MsgType.MEDIA_MESSAGE_ACK_VALUE -> return 0
            else -> AppLog.e("Unsupported Media message type: ${message.type}")
        }
        return 0
    }

    private fun mediaStartRequest(request: Media.Start, channel: Int): Int {
        AppLog.i("Media Start Request %s: session=%d, config_index=%d", Channel.name(channel), request.sessionId, request.configurationIndex)

        aapTransport.setSessionId(channel, request.sessionId)
        return 0
    }

    private fun mediaSinkSetupRequest(request: Media.MediaSetupRequest, channel: Int): Int {

        AppLog.i("Media Sink Setup Request: %d on channel %s", request.type, Channel.name(channel))

        val configResponse = Media.Config.newBuilder().apply {
            status = Media.Config.ConfigStatus.HEADUNIT
            // Use higher maxUnacked for audio to prevent stuttering/drops
            maxUnacked = if (Channel.isAudio(channel)) {
                3
            } else {
                1
            }
            
            addConfigurationIndices(0)
        }.build()
        AppLog.i("Config response: %s", configResponse)
        val msg = AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, configResponse)
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
        } else if (channel == Channel.ID_VID) {
            if (aapTransport.ignoreNextStopRequest) {
                AppLog.i("Video Sink Stopped -> Ignored (Forced Keyframe Request)")
                aapTransport.ignoreNextStopRequest = false
                return 0
            }
            AppLog.i("Video Sink Stopped -> Normal background/transition behavior")
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

    companion object {
        /** Time window for counting consecutive VIDEO_FOCUS_NATIVE requests. */
        private const val NATIVE_FOCUS_DEBOUNCE_MS = 5000L
        /** Number of NATIVE focus requests within the debounce window before stopping. */
        private const val MAX_NATIVE_FOCUS_RETRIES = 3
    }
}

internal class AapControlTouch(private val aapTransport: AapTransport): AapControl {

    override fun execute(message: AapMessage): Int {

        when (message.type) {
            Input.MsgType.BINDINGREQUEST_VALUE -> {
                val request = message.parse(Input.KeyBindingRequest.newBuilder()).build()
                return inputBinding(request, message.channel)
            }
            else -> AppLog.e("Unsupported Input message type: ${message.type}")
        }
        return 0
    }

    private fun inputBinding(request: Input.KeyBindingRequest, channel: Int): Int {
        aapTransport.send(AapMessage(channel, Input.MsgType.BINDINGRESPONSE_VALUE, Input.BindingResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                .build()))
        return 0
    }

}

internal class AapControlSensor(private val aapTransport: AapTransport, private val context: Context): AapControl {

    override fun execute(message: AapMessage): Int {
        when (message.type) {
            Sensors.SensorsMsgType.SENSOR_STARTREQUEST_VALUE -> {
                val request = message.parse(Sensors.SensorRequest.newBuilder()).build()
                return sensorStartRequest(request, message.channel)
            }
            else -> AppLog.e("Unsupported Sensor message type: ${message.type}")
        }
        return 0
    }

    private fun sensorStartRequest(request: Sensors.SensorRequest, channel: Int): Int {
        AppLog.i("Sensor Start Request sensor: %s, minUpdatePeriod: %d", request.type.name, request.minUpdatePeriod)

        val msg = AapMessage(channel, Sensors.SensorsMsgType.SENSOR_STARTRESPONSE_VALUE, Sensors.SensorResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                .build())
        AppLog.i(msg.toString())

        aapTransport.send(msg)
        aapTransport.startSensor(request.type.number)
        
        if (request.type == Sensors.SensorType.NIGHT) {
            AppLog.i("Night sensor requested. Triggering immediate update.")
            val intent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        }
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
            Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_REQUEST_VALUE -> {
                val request = message.parse(Control.ServiceDiscoveryRequest.newBuilder()).build()
                return serviceDiscoveryRequest(request)
            }
            Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE -> {
                val pingRequest = message.parse(Control.PingRequest.newBuilder()).build()
                return pingRequest(pingRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE -> {
                // Response to our heartbeat PingRequest — reset the missed-pong counter.
                aapTransport.onPongReceived()
                return 0
            }
            Control.ControlMsgType.MESSAGE_NAV_FOCUS_REQUEST_VALUE -> {
                val navigationFocusRequest = message.parse(Control.NavFocusRequestNotification.newBuilder()).build()
                return navigationFocusRequest(navigationFocusRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_BYEBYE_REQUEST_VALUE -> {
                val shutdownRequest = message.parse(Control.ByeByeRequest.newBuilder()).build()
                return byebyeRequest(shutdownRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE -> {
                AppLog.i("Byebye Response received")
                return -1
            }
            Control.ControlMsgType.MESSAGE_VOICE_SESSION_NOTIFICATION_VALUE -> {
                val voiceRequest = message.parse(Control.VoiceSessionNotification.newBuilder()).build()
                return voiceSessionNotification(voiceRequest)
            }
            Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_REQUEST_VALUE -> {
                val audioFocusRequest = message.parse(Control.AudioFocusRequestNotification.newBuilder()).build()
                return audioFocusRequest(audioFocusRequest, message.channel)
            }
            Control.ControlMsgType.MESSAGE_CHANNEL_CLOSE_NOTIFICATION_VALUE -> {
                AppLog.i("RX: Channel Close Notification on chan ${message.channel}")
                return 0
            }
            else -> AppLog.e("Unsupported Control message type: ${message.type}")
        }
        return 0
    }


    private fun serviceDiscoveryRequest(request: Control.ServiceDiscoveryRequest): Int {
        AppLog.i("Service Discovery Request: %s", request.phoneName)

        val msg = ServiceDiscoveryResponse(context)
        aapTransport.send(msg)
        return 0
    }

    private fun pingRequest(request: Control.PingRequest, channel: Int): Int {
        val response = Control.PingResponse.newBuilder()
                .setTimestamp(System.nanoTime())
                .build()

        val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, response)
        aapTransport.send(msg)
        return 0
    }

    private fun navigationFocusRequest(request: Control.NavFocusRequestNotification, channel: Int): Int {
        AppLog.i("Navigation Focus Request: %s", request.focusType)

        val response = Control.NavFocusNotification.newBuilder()
                .setFocusType(Control.NavFocusType.NAV_FOCUS_2)
                .build()

        val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_NAV_FOCUS_NOTIFICATION_VALUE, response)
        AppLog.i(msg.toString())

        aapTransport.send(msg)
        return 0
    }

    private fun byebyeRequest(request: Control.ByeByeRequest, channel: Int): Int {
        AppLog.i("!!! RECEIVED BYEBYE REQUEST FROM PHONE !!! Reason: ${request.reason}")
        
        val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_BYEBYE_RESPONSE_VALUE, Control.ByeByeResponse.newBuilder().build())
        AppLog.i("Sending BYEYERESPONSE")
        aapTransport.send(msg)
        Utils.ms_sleep(500)
        AppLog.i("Calling aapTransport.quit(clean=true)")
        aapTransport.quit(clean = true)
        return -1
    }

    private fun voiceSessionNotification(request: Control.VoiceSessionNotification): Int {
        if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_START)
            AppLog.i("Voice Session Notification: START")
        else if (request.status == Control.VoiceSessionNotification.VoiceSessionStatus.VOICE_STATUS_STOP)
            AppLog.i("Voice Session Notification: STOP")
        return 0
    }

    private fun audioFocusRequest(notification: Control.AudioFocusRequestNotification, channel: Int): Int {
        AppLog.i("Audio Focus Request: ${notification.request}")

        val result = aapAudio.requestFocusChange(AudioConfigs.stream(channel), notification.request.number, AudioManager.OnAudioFocusChangeListener {
            val response = Control.AudioFocusNotification.newBuilder()

            focusResponse[notification.request]?.let { newSate ->
                response.focusState = newSate
                AppLog.i("Audio Focus async change: $newSate, system focus change: $it ${systemFocusName[it]}")

                val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE, response.build())
                AppLog.i(msg.toString())
                aapTransport.send(msg)

                // Sync MediaSession
                aapTransport.onAudioFocusStateChanged?.invoke(newSate == Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN)
            }
        })

        // Immediate response
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            val response = Control.AudioFocusNotification.newBuilder()
            focusResponse[notification.request]?.let { state ->
                response.focusState = state
                AppLog.i("Sending immediate AudioFocusNotification: $state")
                val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE, response.build())
                aapTransport.send(msg)

                // Notify transport about focus gain to sync MediaSession
                if (state == Control.AudioFocusNotification.AudioFocusStateType.STATE_GAIN) {
                    aapTransport.onAudioFocusStateChanged?.invoke(true)
                }
            }
        } else {
            // Send LOSS_TRANSIENT instead of LOSS — full LOSS can cause the phone to
            // permanently stop its audio pipeline and in some AA versions tear down the
            // entire projection. Transient loss tells AA to expect focus back shortly.
            AppLog.w("Audio focus request NOT granted immediately ($result). Sending LOSS_TRANSIENT.")
            val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_AUDIO_FOCUS_NOTIFICATION_VALUE,
                Control.AudioFocusNotification.newBuilder().setFocusState(Control.AudioFocusNotification.AudioFocusStateType.STATE_LOSS_TRANSIENT).build())
            aapTransport.send(msg)
            aapTransport.onAudioFocusStateChanged?.invoke(false)
        }

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
            AapControlSensor(aapTransport, context))

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
        val msg = AapMessage(channel, Control.ControlMsgType.MESSAGE_CHANNEL_OPEN_RESPONSE_VALUE, Control.ChannelOpenResponse.newBuilder()
                .setStatus(Common.MessageStatus.STATUS_SUCCESS)
                .build())
        aapTransport.send(msg)

        if (channel == Channel.ID_SEN) {
            aapTransport.send(DrivingStatusEvent(Sensors.SensorBatch.DrivingStatusData.Status.UNRESTRICTED))
        }
        return 0
    }
}