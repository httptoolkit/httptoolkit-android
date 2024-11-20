package tech.httptoolkit.android

import android.os.Parcelable
import android.util.Base64
import com.beust.klaxon.Converter
import com.beust.klaxon.Json
import com.beust.klaxon.JsonValue
import kotlinx.parcelize.Parcelize
import java.io.ByteArrayInputStream
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * ProxyInfo represents user/QR-inputted proxy details. It's an outline of the config for a proxy,
 * which should be sufficient to find & validate ProxyConfig, but it's not immediately usable.
 */
@Parcelize
data class ProxyInfo(
    /**
     * The known ips of the proxy, in priority order.
     */
    val addresses: List<String>,

    /**
     * The port of the proxy
     */
    val port: Int,

    /**
     * A local tunnel port, with an ADB tunnel connected to the proxy. This is less good than a
     * direct network connection, since it's dependent on the ADB server, but it's a useful fallback
     * especially if the proxy computer has a firewall or the network blocks connections.
     */
    @Json(serializeNull = false)
    val localTunnelPort: Int?,

    /**
     * The expected PK hash of the proxy certificate. The certificate itself is obtained from the
     * proxy, as part of validating the connection. This hash is included in QR codes to
     * protect against MitM of our MitM during setup.
     */
    val certFingerprint: String
) : Parcelable

/**
 * The raw proxy config we receive when making a request to
 * android.httptoolkit.tech/config via a proxy.
 * Just a certificate for now, but wrapped in JSON so that we
 * can easily extend it with more fields later on.
 */
@Parcelize
data class ReceivedProxyConfig(
    val certificate: String
) : Parcelable

/**
 * ProxyConfig represents full & recently validated proxy configuration,
 * which can be immediately used to start a VPN connection.
 */
@Parcelize
data class ProxyConfig(
    /**
     * A tested ip for the proxy, which successfully connects.
     */
    val ip: String,

    /**
     * The port of the proxy
     */
    val port: Int,

    /**
     * The HTTPS CA certificate of the proxy, obtained from the proxy itself during setup.
     */
    val certificate: Certificate
) : Parcelable

val CertificateConverter = object: Converter {
    override fun canConvert(cls: Class<*>): Boolean {
        return cls.isAssignableFrom(Certificate::class.java)
    }

    override fun toJson(value: Any): String
            = "\"${Base64.encodeToString((value as X509Certificate).encoded, Base64.NO_WRAP)}\""

    override fun fromJson(jv: JsonValue): X509Certificate {
        val certBytes = Base64.decode(jv.string!!, Base64.DEFAULT)
        val certFactory = CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }

}