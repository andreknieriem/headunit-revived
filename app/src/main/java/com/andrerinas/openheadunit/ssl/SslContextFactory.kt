package com.andrerinas.openheadunit.ssl

import android.content.Context
import java.security.SecureRandom
import javax.net.ssl.SSLContext

object SslContextFactory {

    fun create(context: Context): SSLContext {
        val trustManager = NoCheckTrustManager()
        val keyManager = SingleKeyKeyManager(context)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())

        return sslContext
    }
}
