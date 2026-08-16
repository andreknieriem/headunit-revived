package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.AccessoryConnection
import com.andrerinas.openheadunit.utils.AppLog

/**
 * @param sendIdleProbe asks the phone to prove it is still there. Invoked when a read window closes
 *   empty; see [IdleLinkProbePolicy] for why a silent window is a question rather than an answer.
 */
internal class AapReadSingleMessage(
    connection: AccessoryConnection,
    ssl: AapSsl,
    handler: AapMessageHandler,
    private val sendIdleProbe: () -> Unit = {}
) : AapRead.Base(connection, ssl, handler) {

    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    // Increase to 4MB to handle large 1080p/4K/HEVC I-frames
    private val msgBuffer = ByteArray(4 * 1024 * 1024)
    private val fragmentSizeBuffer = ByteArray(4)

    /** Probes sent since the last byte arrived. Reset by any inbound traffic. */
    private var unansweredProbes = 0

    override fun doRead(connection: AccessoryConnection): Int {
        try {
            // Step 1: Read the encrypted header.
            // A socket read is bounded so that silence is *noticed*; it is not evidence of a dead
            // link on its own, and is answered with a probe rather than a teardown. See
            // IdleLinkProbePolicy. USB keeps waiting indefinitely (0) — its own path detects loss
            // by asking the connection, which TCP cannot answer truthfully.
            val isSocket = connection is com.andrerinas.openheadunit.connection.SocketAccessoryConnection
            val timeout = if (isSocket) SOCKET_READ_WINDOW_MS else 0
            val headerSize = connection.recvBlocking(recvHeader.buf, recvHeader.buf.size, timeout, true)
            if (headerSize != AapMessageIncoming.EncryptedHeader.SIZE) {
                if (headerSize == -1) {
                    AppLog.i("AapRead: Connection closed (EOF). Disconnecting.")
                    return -1
                } else if (headerSize == 0) {
                    if (isSocket) return onSilentReadWindow()
                    return 0
                } else {
                    AppLog.e("AapRead: Partial header read. Expected ${AapMessageIncoming.EncryptedHeader.SIZE}, got $headerSize. Skipping.")
                    return 0
                }
            }

            // Bytes arrived, so the phone is demonstrably there. Any traffic counts, including the
            // response to a probe still in flight.
            unansweredProbes = 0

            recvHeader.decode()

            // Immediate check for Magic Garbage in the header bytes.
            // This is the most reliable path for intentional disconnects from the Helper.
            if (isMagicGarbage(recvHeader.buf, 0, recvHeader.buf.size)) {
                AppLog.i("AapRead: Magic Garbage detected in header. Clean disconnect.")
                return -2
            }

            if (recvHeader.flags == 0x09) {
                // Once header arrived, data should be flowing — 10s timeout is valid here
                val readSize = connection.recvBlocking(fragmentSizeBuffer, 4, 10000, true)
                if(readSize != 4) {
                    AppLog.e("AapRead: Failed to read fragment total size. Skipping.")
                    return 0
                }
            }

            // Step 2: Read the encrypted message body
            // Header arrived so body should follow quickly — 10s timeout
            if (recvHeader.enc_len > msgBuffer.size || recvHeader.enc_len < 0) {
                AppLog.e("AapRead: Invalid message size (${recvHeader.enc_len} bytes). Skipping.")
                return 0
            }
            
            val msgSize = connection.recvBlocking(msgBuffer, recvHeader.enc_len, 10000, true)
            if (msgSize != recvHeader.enc_len) {
                if (msgSize == -1) {
                    AppLog.i("AapRead: Connection closed during body read.")
                    return -1
                }
                AppLog.e("AapRead: Failed to read full message body. Expected ${recvHeader.enc_len}, got $msgSize. Skipping.")
                return 0
            }

            // Step 3: Decrypt the message
            val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)

            if (msg == null) {
                // If decryption failed because of a Magic Garbage signal, return -2 to signal clean quit
                if (ssl is AapSslContext && ssl.isUserDisconnect) {
                    AppLog.i("AapRead: Magic Garbage detected in decryption. Triggering clean disconnect.")
                    return -2
                }
                return 0
            }

            // Step 4: Handle the decrypted message
            handler.handle(msg)
            return 0
        } catch (e: Exception) {
            AppLog.e("AapRead: Error in read loop (ignored): ${e.message}")
            return 0
        }
    }

    /**
     * A read window closed with nothing on it. Either probe and keep the session, or give up.
     *
     * @return a [doRead] result: 0 to keep reading, -1 to tear the session down.
     */
    private fun onSilentReadWindow(): Int =
        when (IdleLinkProbePolicy.onSilentReadWindow(unansweredProbes)) {
            IdleLinkAction.PROBE -> {
                unansweredProbes++
                AppLog.i(
                    "AapRead: no traffic for ${SOCKET_READ_WINDOW_MS / 1000}s, probing phone " +
                        "($unansweredProbes/${IdleLinkProbePolicy.MAX_UNANSWERED_PROBES})"
                )
                sendIdleProbe()
                0
            }

            IdleLinkAction.TEAR_DOWN -> {
                AppLog.e(
                    "AapRead: phone ignored ${IdleLinkProbePolicy.MAX_UNANSWERED_PROBES} probes over " +
                        "${(IdleLinkProbePolicy.MAX_UNANSWERED_PROBES + 1) * SOCKET_READ_WINDOW_MS / 1000}s. " +
                        "Link is dead - disconnecting."
                )
                -1
            }
        }

    private fun isMagicGarbage(buffer: ByteArray, start: Int, length: Int): Boolean {
        if (length < 4) return false // Need at least some bytes to verify
        // Check if at least the first 4 bytes are 0xFF
        for (i in 0 until 4.coerceAtMost(length)) {
            if (buffer[start + i] != 0xFF.toByte()) return false
        }
        return true
    }

    private companion object {
        /**
         * How long a socket read waits before the silence is treated as worth asking about.
         *
         * Long enough that ordinary WiFi jitter does not trigger a probe, short enough that a link
         * which really has gone is noticed inside a minute once the probe budget is spent.
         */
        const val SOCKET_READ_WINDOW_MS = 15000
    }
}
