package com.andrerinas.headunitrevived.ssl

import android.content.Context
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

object SslContextFactory {

    fun create(context: Context): SSLContext {
        // Create a custom TrustManager that trusts all certificates
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        // Create a KeyManager using the existing SingleKeyKeyManager
        val keyManager = SingleKeyKeyManager(context)

        // Create an SSLContext that uses our KeyManager and the trust-all TrustManager
        // Try TLSv1.2 first (for Android 5+), fall back to TLS for Android 4.3
        val sslContext = try {
            SSLContext.getInstance("TLSv1.2")
        } catch (e: Exception) {
            // Android 4.3 and below don't support TLSv1.2, use generic TLS instead
            SSLContext.getInstance("TLS")
        }
        sslContext.init(arrayOf(keyManager), trustAllCerts, SecureRandom())

        return sslContext
    }
}
