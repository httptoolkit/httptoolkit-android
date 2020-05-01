package tech.httptoolkit.android

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.beust.klaxon.Klaxon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
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
        Log.v(TAG, "Validating proxy info $proxyInfo")

        val proxyTests = proxyInfo.addresses.map { address ->
            supervisorScope {
                async {
                    testProxyAddress(
                        address,
                        proxyInfo.port,
                        proxyInfo.certFingerprint
                    )
                }
            }
        }

        // Returns with the first working proxy config (cert & address),
        // or throws if all possible addresses are unreachable/invalid
        // Once the first test succeeds, we cancel any others
        val result = proxyTests.awaitFirst()
        proxyTests.forEach { test ->
            test.cancel()
        }
        return@withContext result
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
            .build()

        val request = Request.Builder()
            .url("http://android.httptoolkit.tech/config")
            .build()

        try {
            val configString = httpClient.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    throw ConnectException("Proxy responded with non-200: ${response.code}")
                }
                response.body!!.string()
            }
            val config = Klaxon().parse<ReceivedProxyConfig>(configString)!!

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

    val certificateAlias = keyStore.getCertificateAlias(proxyConfig.certificate)
    Log.i(TAG, "Cert alias $certificateAlias")

    return when {
        certificateAlias == null -> null
        certificateAlias.startsWith("system:") -> "system"
        certificateAlias.startsWith("user:") -> "user"
        else -> "unknown-store"
    }
}