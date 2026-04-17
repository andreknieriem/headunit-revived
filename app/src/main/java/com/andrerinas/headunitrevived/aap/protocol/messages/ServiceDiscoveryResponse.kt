package com.andrerinas.headunitrevived.aap.protocol.messages

import android.content.Context
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.aap.AapMessage
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.aap.KeyCode
import com.andrerinas.headunitrevived.aap.protocol.AudioConfigs
import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.aap.protocol.proto.Media
import com.andrerinas.headunitrevived.aap.protocol.proto.Sensors
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import com.google.protobuf.Message

class ServiceDiscoveryResponse(private val context: Context)
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto(context)) {

    companion object {
        private fun makeProto(context: Context): Message {
            val settings = App.provide(context).settings

            // Initialize HeadUnitScreenConfig with actual physical screen dimensions
            HeadUnitScreenConfig.init(context, context.resources.displayMetrics, settings)

            val services = mutableListOf<Control.Service>()

            val sensors = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { sources ->
                    sources.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    if (settings.useGpsForNavigation) {
                        sources.addSensors(makeSensorType(Sensors.SensorType.LOCATION))
                    }
                    
                    // Always announce Night sensor, as we control it via NightModeManager
                    sources.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                    AppLog.i("[ServiceDiscovery] Announcing NIGHT sensor support. Strategy: ${settings.nightMode}")
                    
                }.build()
            }.build()

            services.add(sensors)

            val video = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { mediaSinkServiceBuilder ->
                    val codecToRequest = when (settings.videoCodec) {
                        "H.265" -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
                        "Auto" -> if (com.andrerinas.headunitrevived.decoder.VideoDecoder.isHevcSupported()) {
                            Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
                        } else {
                            Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                        }
                        else -> Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    }

                    // Use HeadUnitScreenConfig for negotiated resolution and margins
                    val negotiatedResolution = HeadUnitScreenConfig.negotiatedResolutionType
                    val phoneWidthMargin = HeadUnitScreenConfig.getWidthMargin()
                    val phoneHeightMargin = HeadUnitScreenConfig.getHeightMargin()

                    // Enforce H.265 for 1440p resolution as required by Android Auto
                    val effectiveCodec = if (negotiatedResolution == Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440 ||
                        negotiatedResolution == Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560) {
                        AppLog.i("Resolution is 1440p -> Enforcing H.265 codec")
                        Media.MediaCodecType.MEDIA_CODEC_VIDEO_H265
                    } else {
                        codecToRequest
                    }

                    mediaSinkServiceBuilder.availableType = effectiveCodec
                    mediaSinkServiceBuilder.audioType = Media.AudioStreamType.NONE
                    mediaSinkServiceBuilder.availableWhileInCall = true

                    AppLog.i("[ServiceDiscovery] NegotiatedResolution is: ${HeadUnitScreenConfig.getNegotiatedWidth()}x${HeadUnitScreenConfig.getNegotiatedHeight()}")
                    AppLog.i("[ServiceDiscovery] Margins are: ${phoneWidthMargin}x${phoneHeightMargin}")

                    mediaSinkServiceBuilder.addVideoConfigs(Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                        codecResolution = negotiatedResolution
                        frameRate = when (settings.fpsLimit) {
                            30 -> Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
                            else -> Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._60
                        }
                        setDensity(HeadUnitScreenConfig.getDensityDpi()) // Use actual densityDpi
                        setMarginWidth(phoneWidthMargin)
                        setMarginHeight(phoneHeightMargin)
                        setVideoCodecType(effectiveCodec)
                    }.build())
                }.build()
            }.build()

            services.add(video)

            val input = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also {
                    it.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                        setWidth(HeadUnitScreenConfig.getNegotiatedWidth()) // Use negotiated width
                        setHeight(HeadUnitScreenConfig.getNegotiatedHeight()) // Use negotiated height
                    }.build()
                    
                    if (settings.enableRotary) {
                        AppLog.i("[ServiceDiscovery] Announcing Rotary/Touchpad support")
                        it.touchpad = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                            setWidth(HeadUnitScreenConfig.getNegotiatedWidth())
                            setHeight(HeadUnitScreenConfig.getNegotiatedHeight())
                        }.build()
                    }
                    
                    it.addAllKeycodesSupported(KeyCode.supported)
                }.build()
            }.build()

            services.add(input)

            val audioType = if (settings.useAacAudio) Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC else Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM

            // Always add Audio2 (System Sounds) to keep connection alive
            val audio2 = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AU2
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                    it.availableType = audioType
                    it.audioType = Media.AudioStreamType.SYSTEM
                    it.addAudioConfigs(AudioConfigs.get(Channel.ID_AU2))
                }.build()
            }.build()
            services.add(audio2)

            if (settings.enableAudioSink) {
                if (!AapService.selfMode) {
                    val audio1 = Control.Service.newBuilder().also { service ->
                        service.id = Channel.ID_AU1
                        service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                            it.availableType = audioType
                            it.audioType = Media.AudioStreamType.GUIDANCE
                            it.addAudioConfigs(AudioConfigs.get(Channel.ID_AU1))
                        }.build()
                    }.build()
                    services.add(audio1)
                }

                if (!AapService.selfMode) {
                    val audio0 = Control.Service.newBuilder().also { service ->
                        service.id = Channel.ID_AUD
                        service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also {
                            it.availableType = audioType
                            it.audioType = Media.AudioStreamType.MEDIA
                            it.addAudioConfigs(AudioConfigs.get(Channel.ID_AUD))
                        }.build()
                    }.build()
                    services.add(audio0)
                }
            }

            // Microphone Service (Channel 7) - Always required for AA connection (Assistant)
            val mic = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MIC
                service.mediaSourceService = Control.Service.MediaSourceService.newBuilder().also {
                    it.type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    it.audioConfig = Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                }.build()
            }.build()
            services.add(mic)

            // Bluetooth Service
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

            // Navigation Status Service — head unit receives turn-by-turn data from any AA nav app
            val navigationStatus = Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_NAV
                service.navigationStatusService = Control.Service.NavigationStatusService.newBuilder()
                    .setMinimumIntervalMs(1000)
                    .setType(Control.Service.NavigationStatusService.ClusterType.ImageCodesOnly)
                    .build()
            }.build()
            services.add(navigationStatus)

            return Control.ServiceDiscoveryResponse.newBuilder().apply {
                make = settings.vehicleMake
                model = settings.vehicleModel
                year = settings.vehicleYear
                vehicleId = settings.vehicleId
                headUnitModel = settings.headUnitModel
                headUnitMake = settings.headUnitMake
                headUnitSoftwareBuild = "1"
                headUnitSoftwareVersion = "0.1.0"
                driverPosition = if (settings.rightHandDrive) Control.DriverPosition.DRIVER_POSITION_RIGHT else Control.DriverPosition.DRIVER_POSITION_LEFT
                canPlayNativeMediaDuringVr = false
                hideProjectedClock = false
                setDisplayName(settings.vehicleDisplayName)

                setHeadunitInfo(com.andrerinas.headunitrevived.aap.protocol.proto.Common.HeadUnitInfo.newBuilder().apply {
                    setHeadUnitMake(settings.headUnitMake)
                    setHeadUnitModel(settings.headUnitModel)
                    setMake(settings.vehicleMake)
                    setModel(settings.vehicleModel)
                    setYear(settings.vehicleYear)
                    setVehicleId(settings.vehicleId)
                    setHeadUnitSoftwareBuild("1")
                    setHeadUnitSoftwareVersion("0.1.0")
                }.build())

                addAllServices(services)
            }.build()
        }

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor {
            return Control.Service.SensorSourceService.Sensor.newBuilder()
                    .setType(type).build()
        }
    }
}
