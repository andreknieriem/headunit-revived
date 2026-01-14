package com.andrerinas.headunitrevived.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat

class Settings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isConnectingDevice(deviceCompat: UsbDeviceCompat): Boolean {
        val allowDevices = prefs.getStringSet("allow-devices", null) ?: return false
        return allowDevices.contains(deviceCompat.uniqueName)
    }

    var allowedDevices: Set<String>
        get() = prefs.getStringSet("allow-devices", HashSet<String>())!!
        set(devices) {
            prefs.edit().putStringSet("allow-devices", devices).apply()
        }

    var networkAddresses: Set<String>
        get() = prefs.getStringSet("network-addresses", HashSet<String>())!!
        set(addrs) {
            prefs.edit().putStringSet("network-addresses", addrs).apply()
        }

    var bluetoothAddress: String
        get() = prefs.getString("bt-address", "40:EF:4C:A3:CB:A5")!!
        set(value) = prefs.edit().putString("bt-address", value).apply()

    var lastKnownLocation: Location
        get() {
            val latitude = prefs.getLong("last-loc-latitude", (32.0864169).toLong())
            val longitude = prefs.getLong("last-loc-longitude", (34.7557871).toLong())

            val location = Location("")
            location.latitude = latitude.toDouble()
            location.longitude = longitude.toDouble()
            return location
        }
        set(location) {
            prefs.edit()
                .putLong("last-loc-latitude", location.latitude.toLong())
                .putLong("last-loc-longitude", location.longitude.toLong())
                .apply()
        }

    var resolutionId: Int
        get() = prefs.getInt("resolutionId", 0)
        set(value) = prefs.edit().putInt("resolutionId", value).apply()

    var micSampleRate: Int
        get() = prefs.getInt("mic-sample-rate", 16000)
        set(sampleRate) {
            prefs.edit().putInt("mic-sample-rate", sampleRate).apply()
        }

    var useGpsForNavigation: Boolean
        get() = prefs.getBoolean("gps-navigation", true)
        set(value) {
            prefs.edit().putBoolean("gps-navigation", value).apply()
        }

    var nightMode: NightMode
        get() {
            val value = prefs.getInt("night-mode", 0)
            val mode = NightMode.fromInt(value)
            return mode!!
        }
        set(nightMode) {
            prefs.edit().putInt("night-mode", nightMode.value).apply()
        }

    var keyCodes: MutableMap<Int, Int>
        get() {
            val set = prefs.getStringSet("key-codes", mutableSetOf())!!
            val map = mutableMapOf<Int, Int>()
            set.forEach {
                val codes = it.split("-")
                map[codes[0].toInt()] = codes[1].toInt()
            }
            return map
        }
        set(codesMap) {
            val list: List<String> = codesMap.map { "${it.key}-${it.value}" }
            prefs.edit().putStringSet("key-codes", list.toSet()).apply()
        }

    var debugMode: Boolean
        get() = prefs.getBoolean("debug-mode", false)
        set(value) {
            prefs.edit().putBoolean("debug-mode", value).apply()
        }

    var viewMode: ViewMode
        get() {
            val value = prefs.getInt("view-mode", 0)
            return ViewMode.fromInt(value) ?: ViewMode.SURFACE
        }
        set(viewMode) {
            prefs.edit().putInt("view-mode", viewMode.value).apply()
        }

    var dpiPixelDensity: Int
        get() = prefs.getInt("dpi-pixel-density", 0) // Default 0 for Auto
        set(value) {
            prefs.edit().putInt("dpi-pixel-density", value).apply()
        }

    var marginLeft: Int
        get() = prefs.getInt("margin-left", 0)
        set(value) { prefs.edit().putInt("margin-left", value).apply() }

    var marginTop: Int
        get() = prefs.getInt("margin-top", 0)
        set(value) { prefs.edit().putInt("margin-top", value).apply() }

    var marginRight: Int
        get() = prefs.getInt("margin-right", 0)
        set(value) { prefs.edit().putInt("margin-right", value).apply() }

    var marginBottom: Int
        get() = prefs.getInt("margin-bottom", 0)
        set(value) { prefs.edit().putInt("margin-bottom", value).apply() }

    var startInFullscreenMode: Boolean
        get() = prefs.getBoolean("start-in-fullscreen-mode", true)
        set(value) { prefs.edit().putBoolean("start-in-fullscreen-mode", value).apply() }

    var forceSoftwareDecoding: Boolean
        get() = prefs.getBoolean("force-software-decoding", false)
        set(value) { prefs.edit().putBoolean("force-software-decoding", value).apply() }

    var forceLegacyDecoder: Boolean
        get() = prefs.getBoolean("force-legacy-decoder", false)
        set(value) { prefs.edit().putBoolean("force-legacy-decoder", value).apply() }

    var videoCodec: String
        get() = prefs.getString("video-codec", "Auto")!!
        set(value) { prefs.edit().putString("video-codec", value).apply() }

    var fpsLimit: Int
        get() = prefs.getInt("fps-limit", 60)
        set(value) { prefs.edit().putInt("fps-limit", value).apply() }

    var hasAcceptedDisclaimer: Boolean
        get() = prefs.getBoolean("has-accepted-disclaimer", false)
        set(value) { prefs.edit().putBoolean("has-accepted-disclaimer", value).apply() }

    @SuppressLint("ApplySharedPref")
    fun commit() {
        prefs.edit().commit()
    }

    enum class Resolution(val id: Int, val resName: String, val width: Int, val height: Int, val codec: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType?) {
        AUTO(0, "Auto",0, 0, null),
        _800x480(1, "480p", 800, 480, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480),
        _1280x720(2, "720p", 1280, 720, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720),
        _1920x1080(3, "1080p", 1920, 1080, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080),
        _2560x1440(4, "1440p", 2560, 1440, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440);

        // TODO: Portrait and higher Resolutions later
        /*        _2560x1440(4, "2560x1440 (Experimental)", 2560,1440, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440),
        _3840x2160(5, "3840x2160 (Experimental)", 3840,2160, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160),
        _720x1280(6, "720x1280 (Portrait)", 720,1280, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280),
        _1080x1920(7, "1080x1920 (Portrait)", 1080,1920, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920),
        _1440x2560(8, "1440x2560 (Portrait)", 1440,2560, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560),
        _2160x3840(9, "2160x3840 (Portrait)", 2160,3840, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2160x3840);*/
        companion object {
            private val map = values().associateBy(Resolution::id)
            fun fromId(id: Int) = map[id]
            val allRes: Array<String>
                get() = values().map { it.resName }.toTypedArray()
            val allResolutions: Array<Resolution>
                get() = values()
        }
    }

    enum class NightMode(val value: Int) {
        AUTO(0),
        DAY(1),
        NIGHT(2),
        AUTO_WAIT_GPS(3),
        NONE(4);

        companion object {
            private val map = NightMode.values().associateBy(NightMode::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    companion object {
        val MicSampleRates = listOf(8000, 16000, 24000, 32000, 44100, 48000) // Changed to List

        fun getNextMicSampleRate(currentRate: Int): Int {
            val currentIndex = MicSampleRates.indexOf(currentRate)
            return if (currentIndex != -1 && currentIndex < MicSampleRates.size - 1) {
                MicSampleRates[currentIndex + 1]
            } else {
                MicSampleRates.first() // Loop back to first if at end or not found
            }
        }

        // NightMode is now an enum, so we can iterate its values directly
    }

    enum class ViewMode(val value: Int) {
        SURFACE(0),
        TEXTURE(1);

        companion object {
            private val map = values().associateBy(ViewMode::value)
            fun fromInt(value: Int) = map[value]
        }
    }

}
