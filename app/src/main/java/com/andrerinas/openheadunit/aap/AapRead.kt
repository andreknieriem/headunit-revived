package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.connection.AccessoryConnection
import com.andrerinas.openheadunit.connection.SocketAccessoryConnection
import com.andrerinas.openheadunit.decoder.MicRecorder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.utils.Settings

internal interface AapRead {
    fun read(): Int

    abstract class Base internal constructor(
            private val connection: AccessoryConnection?,
            internal val ssl: AapSsl,
            internal val handler: AapMessageHandler) : AapRead {

        override fun read(): Int {
            if (connection == null) {
                AppLog.e("No connection.")
                return -1
            }

            return doRead(connection)
        }

        protected abstract fun doRead(connection: AccessoryConnection): Int
    }

    object Factory {
        fun create(
            connection: AccessoryConnection,
            transport: AapTransport,
            recorder: MicRecorder,
            aapAudio: AapAudio,
            aapVideo: AapVideo,
            settings: Settings,
            context: Context,
            onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null,
            onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null
        ): AapRead {
            val handler = AapMessageHandlerType(
                transport,
                recorder,
                aapAudio,
                aapVideo,
                settings,
                context,
                onAaMediaMetadata,
                onAaPlaybackStatus
            )

            // Read framing is a transport-shape question, not a handshake-timing one:
            // every socket-backed connection (Nearby, WiFi Direct, Hotspot, manual IP) frames
            // one AA message per blocking read, same as it always has. Only USB/libusb bulk
            // transfers can batch multiple messages into one read and need the FIFO reassembly
            // in AapReadMultipleMessages. Do NOT key this off isSingleMessage - that flag only
            // controls the Nearby-specific handshake settle-delay/drain skip in
            // AapTransport.handshake() and is unrelated to read framing.
            return if (connection is SocketAccessoryConnection)
                AapReadSingleMessage(connection, transport.ssl, handler, transport::sendIdleProbe)
            else
                AapReadMultipleMessages(connection, transport.ssl, handler)
        }
    }
}
