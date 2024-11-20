package tech.httptoolkit.android

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.beust.klaxon.Klaxon
import io.sentry.Sentry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

private val TAG = formatTag("tech.httptoolkit.android.ProxySetup")

class ResponseException(message: String) : ConnectException(message)

// Takes an android.httptoolkit.tech/connect URI, extracts & parses the connection config
// within, into a format ready for testing and then usage.
fun parseConnectUri(uri: Uri): ProxyInfo {
    val dataBase64 = uri.getQueryParameter("data")

    // Data is a JSON string, encoded as base64, to solve escaping & ensure that the
    // most popular standard barcode apps treat it as a single URL (some get confused by
    // JSON that contains ip addresses otherwise)
    val data = String(Base64.decode(dataBase64, Base64.URL_SAFE), StandardCharsets.UTF_8)
    Log.d(TAG, "URL data is $data")

    return Klaxon().parse<ProxyInfo>(data)
        ?: throw IllegalArgumentException("Invalid proxy JSON: $data")
}

suspend fun getProxyConfig(proxyInfo: ProxyInfo): ProxyConfig {
    return withContext(Dispatchers.IO) {
        return@withContext supervisorScope {
            Log.v(TAG, "Validating proxy info $proxyInfo")
            val proxyTests = proxyInfo.addresses.map { address ->
                async {
                    testProxyAddress(
                        address,
                        proxyInfo.port,
                        proxyInfo.certFingerprint
                    )
                }
            }

            Log.v(TAG, "Proxy tests started")

            // Return with the first working proxy config (cert & address)
            // (or throw if all addresses are unreachable/invalid)
            try {
                return@supervisorScope proxyTests.awaitFirst()
            } catch (e: Exception) {
                if (proxyInfo.localTunnelPort == null) throw e;

                // If all network connections fail, and we have a local ADB tunnel, fallback to
                // using that connection instead.
                return@supervisorScope testProxyAddress(
                    "127.0.0.1",
                    proxyInfo.localTunnelPort,
                    proxyInfo.certFingerprint
                )
            }
        }
    }
}

private suspend fun testProxyAddress(
    address: String,
    port: Int,
    expectedFingerprint: String
): ProxyConfig {
    return withContext(Dispatchers.IO) {
        val certFactory = CertificateFactory.getInstance("X.509")

        val httpClient = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(address, port)))
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        Log.i(TAG, "Testing proxy $address:$port")

        try {
            val config = try {
                val configString = request(httpClient, "http://android.httptoolkit.tech/config")
                Klaxon().parse<ReceivedProxyConfig>(configString)!!
            } catch (e: ResponseException) {
                // If we connected but the response was bad, maybe we're reconnecting to an app
                // that isn't explicitly expecting an Android client. Retry requesting just the cert.
                val certString = request(httpClient, "http://amiusing.httptoolkit.tech/certificate")
                ReceivedProxyConfig(certString)
            }

            val foundCert = certFactory.generateCertificate(
                ByteArrayInputStream(config.certificate.toByteArray(Charsets.UTF_8))
            ) as X509Certificate
            val foundCertFingerprint = getCertificateFingerprint(foundCert)

            if (foundCertFingerprint == expectedFingerprint) {
                ProxyConfig(
                    address,
                    port,
                    foundCert
                )
            } else {
                throw CertificateException(
                    "Proxy returned mismatched certificate: '${
                    expectedFingerprint
                    }' != '$foundCertFingerprint' ($address)"
                )
            }
        } catch (e: Exception) {
            Log.i(TAG, "Error testing proxy address $address: $e")
            throw e
        }
    }
}

suspend fun request(httpClient: OkHttpClient, url: String): String {
    return withContext(Dispatchers.IO) {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code != 200) {
                throw ResponseException("Proxy responded with non-200: ${response.code}")
            }
            response.body!!.string()
        }
    }
}

fun getCertificateFingerprint(cert: X509Certificate): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(cert.publicKey.encoded)
    val fingerprint = md.digest()
    return Base64.encodeToString(fingerprint, Base64.NO_WRAP)
}


/**
 * Does the device have a PIN/pattern/password set? Relevant because if not, the cert
 * setup will require the user to add one. This is best guess - not 100% accurate.
 */
fun isDeviceSecured(context: Context): Boolean {
    val keyguardManager = getSystemService(context, KeyguardManager::class.java)

    return when {
        // If we can't get a keyguard manager for some reason, assume there's no pin set
        keyguardManager == null -> false
        // If possible, accurately report device status
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> keyguardManager.isDeviceSecure
        // Imperfect but close though: also returns true if the device has a locked SIM card.
        else -> keyguardManager.isKeyguardSecure
    }
}

/**
 * Returns the name of the cert store (if the cert is trusted) or null (if not)
 */
fun whereIsCertTrusted(proxyConfig: ProxyConfig): String? {
    val keyStore = KeyStore.getInstance("AndroidCAStore")
    keyStore.load(null, null)

    val proxyCertData = proxyConfig.certificate.encoded

    val aliases = keyStore.aliases()

    val proxyCertAliases = aliases.toList().filter { alias ->
        val storedCert = keyStore.getCertificate(alias)
        val certData = storedCert?.encoded
        return@filter certData != null && certData.contentEquals(proxyCertData)
    }

    Log.i(TAG, "Proxy cert aliases: $proxyCertAliases")

    return when {
        proxyCertAliases.isEmpty() -> null
        proxyCertAliases.any { alias -> alias.startsWith("system:") } -> "system"
        proxyCertAliases.any { alias -> alias.startsWith("user:") } -> "user"
        else -> {
            Sentry.captureMessage("Cert has no recognizable aliases")
            return "unknown-store"
        }
    }
}