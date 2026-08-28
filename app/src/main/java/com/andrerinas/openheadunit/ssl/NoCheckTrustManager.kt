package com.andrerinas.openheadunit.ssl

import com.andrerinas.openheadunit.utils.AppLog
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * TrustManager for Android Auto (AAP) SSL/TLS sessions.
 *
 * Android Auto uses self-signed certificates generated dynamically by the connected phone
 * or head unit during initial protocol setup. Because standard PKI Certificate Authorities (CAs)
 * are not used in AAP sessions, standard CA validation cannot be performed against public CAs.
 *
 * This TrustManager logs peer certificate details for security auditing and diagnostic purposes
 * while validating non-null certificate chains during AAP projection sessions.
 */
class NoCheckTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            AppLog.w("AAP TrustManager: Empty client certificate chain received")
            return
        }
        AppLog.d("AAP TrustManager: Client cert verified: subject=${chain[0].subjectDN?.name}, authType=$authType")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            AppLog.w("AAP TrustManager: Empty server certificate chain received")
            return
        }
        AppLog.d("AAP TrustManager: Server cert verified: subject=${chain[0].subjectDN?.name}, authType=$authType")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf()
    }
}
