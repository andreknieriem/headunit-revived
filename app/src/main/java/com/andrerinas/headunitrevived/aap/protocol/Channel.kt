package com.andrerinas.headunitrevived.aap.protocol

object Channel {

    const val ID_CTR = 0
    const val ID_INP = 1
    const val ID_SEN = 2
    const val ID_VID = 3
    const val ID_MIC = 4
    const val ID_AUD = 5
    const val ID_AU2 = 6
    const val ID_AU1 = 7
    const val ID_BTH = 8
    const val ID_MPB = 9
    const val ID_NAV = 10
    const val ID_NOT = 11
    const val ID_NOTI = 11
    const val ID_PHONE = 12
    const val ID_WIFI = 13

    fun name(channel: Int): String {
        when (channel) {
            ID_CTR -> return "CONTROL"
            ID_VID -> return "VIDEO"
            ID_INP -> return "INPUT"
            ID_SEN -> return "SENSOR"
            ID_MIC -> return "MIC"
            ID_AUD -> return "AUDIO"
            ID_AU1 -> return "AUDIO1"
            ID_AU2 -> return "AUDIO2"
            ID_BTH -> return "BLUETOOTH"
            ID_MPB -> return "MUSIC_PLAYBACK"
            ID_NAV -> return "NAVIGATION_DIRECTIONS"
            ID_NOTI -> return "NOTIFICATION"
            ID_PHONE -> return "PHONE_STATUS"
        }
        return "UNK"
    }

    fun isAudio(chan: Int): Boolean {
        return chan == Channel.ID_AUD || chan == Channel.ID_AU1 || chan == Channel.ID_AU2
    }
}