package com.andrerinas.headunitrevived.ssl

import android.content.Context
import android.util.Base64
import com.andrerinas.headunitrevived.R
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

    init {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        ks.setCertificateEntry(DEFAULT_ALIAS, certificate)
        ks.setKeyEntry(DEFAULT_ALIAS, privateKey, charArrayOf(), arrayOf(certificate))

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

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String {
        return delegate.chooseServerAlias(keyType, issuers, socket)
    }

    override fun getCertificateChain(alias: String?): Array<X509Certificate> {
        return delegate.getCertificateChain(alias)
    }

    override fun getPrivateKey(alias: String?): PrivateKey {
        return delegate.getPrivateKey(alias)
    }

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String {
        return DEFAULT_ALIAS
    }

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String {
        return DEFAULT_ALIAS
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