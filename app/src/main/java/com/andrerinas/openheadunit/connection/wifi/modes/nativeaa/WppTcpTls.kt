package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Turns an accepted TCP connection into the TLS half of a WPP session.
 *
 * The phone dials us and then puts its own end in server mode with client authentication required,
 * the same way it does for the projection session on 5288. So accepting the connection does not
 * make us the TLS server: we send the first handshake record and present the certificate. Answering
 * as a server instead leaves both ends waiting to read until the phone gives up.
 *
 * Its own file so the role is one call the tests can drive against a peer configured like the phone.
 */
object WppTcpTls {

    /** Wraps [raw] as the TLS client, ready to hand shake. Closing it closes [raw]. */
    fun clientSocket(factory: SSLSocketFactory, raw: Socket): SSLSocket =
        (factory.createSocket(raw, raw.inetAddress?.hostAddress, raw.port, true) as SSLSocket)
            .apply { useClientMode = true }
}
