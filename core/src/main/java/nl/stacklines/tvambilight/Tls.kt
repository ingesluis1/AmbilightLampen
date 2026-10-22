package nl.stacklines.tvambilight

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Apparaten in huis (tv, Hue Bridge) gebruiken https met een eigen certificaat dat Android niet kent.
 * - trustAll: voor de tv-API op 127.0.0.1 (de tv zelf).
 * - pinned: voor de Hue Bridge; accepteert alleen het certificaat waarvan de vingerafdruk bij het koppelen is onthouden.
 */
object Tls {
    /** Staat in de foutmelding als een Bridge een ander certificaat laat zien dan bij het koppelen. */
    const val PIN_MISMATCH = "certificaat wijkt af van de koppeling"

    val trustAll: SSLSocketFactory by lazy { factory { } }

    @Volatile private var cache: Pair<String, SSLSocketFactory>? = null

    /**
     * pin = verwachte SHA-256 van het certificaat (hex); null = alles accepteren (alleen bij een nieuwe koppeling).
     * seen krijgt de vingerafdruk die de server liet zien, om die bij het koppelen te bewaren.
     */
    fun pinned(pin: String?, seen: ((String) -> Unit)?): SSLSocketFactory {
        if (pin != null && seen == null) cache?.let { if (it.first == pin) return it.second }
        val f = factory { chain ->
            val fp = fingerprint(chain[0])
            seen?.invoke(fp)
            if (pin != null && !fp.equals(pin, ignoreCase = true)) throw CertificateException(PIN_MISMATCH)
        }
        // Zelfde factory hergebruiken: dan blijft de verbinding met de Bridge open tussen opdrachten
        if (pin != null && seen == null) cache = pin to f
        return f
    }

    fun fingerprint(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }

    private fun factory(check: (Array<X509Certificate>) -> Unit): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("geen certificaat")
                check(chain)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }.socketFactory
    }
}
