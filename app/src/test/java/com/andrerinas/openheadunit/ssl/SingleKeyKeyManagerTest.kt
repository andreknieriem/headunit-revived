package com.andrerinas.openheadunit.ssl

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Covers the certificate and key the app actually ships, read from `res/raw` rather than copied, so
 * a swap of either is exercised here too.
 *
 * The engine hooks are the point. X509ExtendedKeyManager defaults both to null and does not fall
 * back to the Socket variants, so a missing server half means no certificate is offered and every
 * inbound TLS handshake aborts, which is what WppTcpServer hit on hardware.
 */
class SingleKeyKeyManagerTest {

    private val manager = SingleKeyKeyManager(certificate(), privateKey())

    @Test
    fun `an alias is chosen in client mode, engine-backed and socket-backed alike`() {
        assertNotNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertNotNull(manager.chooseEngineClientAlias(arrayOf("RSA"), null, null))
    }

    @Test
    fun `an alias is chosen in server mode on the engine hook`() {
        assertNotNull(manager.chooseEngineServerAlias("RSA", null, null))
    }

    @Test
    fun `the chosen server alias resolves to a usable key and chain`() {
        val alias = manager.chooseEngineServerAlias("RSA", null, null)
        assertNotNull(manager.getPrivateKey(alias))
        assertTrue(manager.getCertificateChain(alias).orEmpty().isNotEmpty())
    }

    @Test
    fun `the socket-backed server hook agrees with the engine one`() {
        assertNotNull(manager.chooseServerAlias("RSA", null, null))
    }

    private fun certificate(): X509Certificate =
        File(RAW, "cert").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun privateKey() = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                File(RAW, "privkey").readText()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
            )
        )
    )

    private companion object {
        /** Gradle runs unit tests with the module directory as the working directory. */
        const val RAW = "src/main/res/raw"
    }
}
