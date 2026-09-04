package com.andrerinas.openheadunit.ssl

import android.content.Context
import android.util.Base64
import com.andrerinas.openheadunit.R
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

class SingleKeyKeyManager(certificate: X509Certificate, privateKey: PrivateKey): X509ExtendedKeyManager() {

    constructor(context: Context)
        : this(createCertificate(context), createPrivateKey(context))

    private val delegate: X509KeyManager

    /**
     * The alias as the keystore actually holds it, which is not always the one we asked for: a
     * PKCS12 store lowercases them where a BKS store does not. Handing the delegate a name it does
     * not know back gets a null key and chain, and the handshake fails with nothing to present.
     */
    private val alias: String

    init {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        ks.setCertificateEntry(DEFAULT_ALIAS, certificate)
        ks.setKeyEntry(DEFAULT_ALIAS, privateKey, charArrayOf(), arrayOf(certificate))
        alias = ks.aliases().toList().firstOrNull() ?: DEFAULT_ALIAS

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        delegate = kmf.keyManagers[0] as X509KeyManager
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
        return delegate.getClientAliases(keyType, issuers)
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
        return delegate.getServerAliases(keyType, issuers)
    }

    // Nullable, as JSSE declares them: the delegate may legally answer null for an alias it does
    // not hold, and a non-null Kotlin type turns that into an NPE thrown from inside the handshake
    // instead of a TLS alert the peer can read.
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? {
        return delegate.chooseServerAlias(keyType, issuers, socket)
    }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        return delegate.getCertificateChain(alias)
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        return delegate.getPrivateKey(alias)
    }

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String {
        return alias
    }

    // X509ExtendedKeyManager's two engine hooks default to null and do not fall back to the Socket
    // variants above, and Conscrypt's engine-backed sockets ask the engine hook. Without the server
    // half we choose no certificate in server mode, send none, and every handshake aborts.
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String {
        return alias
    }

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String {
        return alias
    }

    companion object {
        private const val DEFAULT_ALIAS = "defaultSingleKeyAlias"

        private fun createCertificate(context: Context): X509Certificate {
            val certStream = context.resources.openRawResource(R.raw.cert)
            val certificateFactory = CertificateFactory.getInstance("X.509")
            return certificateFactory.generateCertificate(certStream) as X509Certificate
        }

        private fun createPrivateKey(context: Context): PrivateKey {
            val privateKeyContent = context.resources
                    .openRawResource(R.raw.privkey)
                    .bufferedReader().use { it.readText() }
                    .replace("\n", "")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
            val keySpecPKCS8 = PKCS8EncodedKeySpec(Base64.decode(privateKeyContent, Base64.DEFAULT))
            val kf = KeyFactory.getInstance("RSA")
            return kf.generatePrivate(keySpecPKCS8)
        }
    }
}