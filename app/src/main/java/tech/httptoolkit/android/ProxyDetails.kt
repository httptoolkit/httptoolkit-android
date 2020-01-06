package tech.httptoolkit.android

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import java.security.cert.Certificate

/**
 * ProxyInfo represents user/QR-inputted proxy details. It's an outline of the config for a proxy,
 * which should be sufficient to find & validate ProxyConfig, but it's not immediately usable.
 */
@Parcelize
data class ProxyInfo(
    /**
     * The known ips of the proxy, in priority order.
     */
    val ips: List<String>,

    /**
     * The port of the proxy
     */
    val port: Int,

    /**
     * The expected hash of the proxy certificate. The certificate itself is obtained from the
     * proxy, as part of validating the connection. This hash is included in QR codes to
     * protect against MitM of our MitM during setup.
     */
    val certificateHash: String
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