package com.andrerinas.headunitrevived.aap.protocol.messages

import android.content.Context
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.KeyCode
import com.andrerinas.headunitrevived.aap.protocol.AudioConfigs
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.Screen
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.aap.protocol.proto.Media
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import com.google.protobuf.Message

/**
 * @author alex gavrishev
 *
 * @date 13/02/2017.
 */
class ServiceDiscoveryResponse(private val context: Context)
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.SERVICEDISCOVERYRESPONSE_VALUE, makeProto(context)) {

    companion object {
        private fun makeProto(context: Context): Message {
            val displayMetrics = context.resources.displayMetrics
            var width = displayMetrics.widthPixels
            var height = displayMetrics.heightPixels
            val densityDpi = displayMetrics.densityDpi
            AppLog.i("ServiceDiscoveryResponse: Actual width=$width, height=$height, densityDpi=$densityDpi")

            // Lie to the phone for certain non-standard resolutions to improve compatibility.
            if (width == 1280 && height == 736) {
                AppLog.i("Overriding reported resolution to 1280x720 for compatibility.")
                width = 1280
                height = 720
            }

            val settings = App.provide(context).settings // Get settings from App component
            val resolution = Settings.Resolution.fromId(settings.resolutionId)!!

            val videoCodecResolutionType = if (resolution.id == 0) {
                Screen.forResolution(width, height)
            } else {
                resolution.codec!!
            }
            val screen = Screen.forResolution(videoCodecResolutionType)

            val services = mutableListOf<Control.Service>()

            val sensors = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { sources ->
                    sources.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    if (settings.useGpsForNavigation) {
                        sources.addSensors(makeSensorType(Sensors.SensorType.LOCATION))
                    }
                    if (settings.nightMode != Settings.NightMode.NONE){
                        sources.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                    }
                }.build()
            }.build()

            services.add(sensors)

            val video = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                    it.availableType = Media.MediaCodecType.VIDEO
                    it.audioType = Media.AudioStreamType.NONE
                    it.availableWhileInCall = true
                    it.addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                        marginHeight = (height - screen.height) / 2
                        marginWidth = (width - screen.width) / 2
                        codecResolution = videoCodecResolutionType
                        frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._60
                        density = densityDpi
                    }.build())
                }.build()
            }.build()

            services.add(video)

            val input = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also {
                    it.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                        setWidth(width)
                        setHeight(height)
                    }.build()
                    it.addAllKeycodesSupported(KeyCode.supported)
                }.build()
            }.build()

            services.add(input)

            val audio1 = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AU1
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                    it.availableType = Media.MediaCodecType.AUDIO
                    it.audioType = Media.AudioStreamType.SPEECH
                    it.addAudioConfigs(AudioConfigs.get(Channel.ID_AU1))
                }.build()
            }.build()
            services.add(audio1)

            val audio2 = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AU2
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                    it.availableType = Media.MediaCodecType.AUDIO
                    it.audioType = Media.AudioStreamType.SYSTEM
                    it.addAudioConfigs(AudioConfigs.get(Channel.ID_AU2))
                }.build()
            }.build()
            services.add(audio2)

            val audio0 = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AUD
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                    it.availableType = Media.MediaCodecType.AUDIO
                    it.audioType = Media.AudioStreamType.MEDIA
                    it.addAudioConfigs(AudioConfigs.get(Channel.ID_AUD))
                }.build()
            }.build()
            services.add(audio0)

            val mic = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MIC
                service.mediaSourceService = Control.Service.MediaSourceService.newBuilder().also {
                    it.type = Media.MediaCodecType.AUDIO
                    it.audioConfig = Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                }.build()
            }.build()
            services.add(mic)

            if (settings.bluetoothAddress.isNotEmpty()) {
                val bluetooth = Control.Service.newBuilder().also { service ->
                    service.id = Channel.ID_BTH
                    service.bluetoothService = Control.Service.BluetoothService.newBuilder().also {
                        it.carAddress = settings.bluetoothAddress
                        it.addAllSupportedPairingMethods(
                                listOf(Control.BluetoothPairingMethod.A2DP,
                                        Control.BluetoothPairingMethod.HFP)
                        )
                    }.build()
                }.build()
                services.add(bluetooth)
            } else {
                AppLog.i("BT MAC Address is empty. Skip bluetooth service")
            }

            val mediaPlaybackStatus = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MPB
                service.mediaPlaybackService = Control.Service.MediaPlaybackStatusService.newBuilder().build()
            }.build()
            services.add(mediaPlaybackStatus)

            return Control.ServiceDiscoveryResponse.newBuilder().apply {
                make = "Google"
                model = "Android Auto"
                year = "2023"
                vehicleId = "Generic"
                headUnitModel = "Generic Headunit"
                headUnitMake = "Generic Make"
                headUnitSoftwareBuild = "1.0"
                headUnitSoftwareVersion = "1.0"
                driverPosition = true
                canPlayNativeMediaDuringVr = false
                hideProjectedClock = false
                addAllServices(services)
            }.build()
        }

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor {
            return Control.Service.SensorSourceService.Sensor.newBuilder()
                    .setType(type).build()
        }
    }
}
