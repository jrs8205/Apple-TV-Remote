package com.jrs8205.appletvremote.lgtv

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use for the TV's self-signed certificate. With a [pinned] SPKI SHA-256 only that
 * key passes; without one any certificate passes and [seen] records its hash so it can be pinned.
 */
@Suppress("CustomX509TrustManager")
internal class LgTvTrust(private val pinned: String?) : X509TrustManager {

    @Volatile var seen: String? = null
        private set

    @Volatile var rejected: Boolean = false
        private set

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("the TV sent no certificate")
        val hash = spkiSha256(leaf)
        if (pinned != null && !pinned.equals(hash, ignoreCase = true)) {
            rejected = true
            throw CertificateException("the TV's certificate does not match the paired one")
        }
        seen = hash
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun spkiSha256(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded).joinToString("") { "%02x".format(it) }
    }
}
